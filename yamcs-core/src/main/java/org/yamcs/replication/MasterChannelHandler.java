package org.yamcs.replication;

import static org.yamcs.replication.MessageType.protoToNetty;
import static org.yamcs.replication.ReplicationServer.workerGroup;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import org.yamcs.logging.Log;
import org.yamcs.replication.ReplicationMaster.SlaveServer;
import org.yamcs.replication.protobuf.Request;
import org.yamcs.replication.protobuf.Response;
import org.yamcs.replication.protobuf.Wakeup;

import com.google.protobuf.InvalidProtocolBufferException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * 
 * runs on the master side sending data to slave
 *
 */
public class MasterChannelHandler extends ChannelInboundHandlerAdapter {
    ReplicationMaster replMaster;

    private ChannelHandlerContext channelHandlerContext;
    Request req;
    ChannelFuture dataHandlingFuture;
    ReplicationFile currentFile;
    long nextTxToSend;
    ReplicationTail fileTail;
    final Log log;
    SlaveServer slaveServer;

    // called when we are TCP client, we first send a wakeup message and then we receive the request
    public MasterChannelHandler(ReplicationMaster master, SlaveServer slaveServer) {
        this.replMaster = master;
        this.slaveServer = slaveServer;
        this.req = null;
        log = new Log(MasterChannelHandler.class, master.getYamcsInstance());
    }

    // called when we are connected to a TCP server
    public MasterChannelHandler(ReplicationMaster master, Request req) {
        this.replMaster = master;
        this.req = req;
        log = new Log(MasterChannelHandler.class, master.getYamcsInstance());
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;
        int sizetype = buf.readInt();
        byte msgType = (byte) (sizetype >> 24);
        if (msgType == MessageType.REQUEST) {
            try {
                this.req = MessageType.nettyToProto(buf, Request.newBuilder()).build();
                processRequest();
            } catch (InvalidProtocolBufferException e) {
                log.warn("Failed to decode REQUEST message", e);
                ctx.close();
            }
        } else if (msgType == MessageType.RESPONSE) {
            try {
                Response resp = MessageType.nettyToProto(buf, Response.newBuilder()).build();
                if (resp.getResult() != 0) {
                    log.warn("Received negative response: {}", resp.getErrorMsg());
                    return;
                } else {
                    log.info("Received response {}", resp);
                }
            } catch (InvalidProtocolBufferException e) {
                log.warn("Failed to decode RESPONSE message", e);
            }
        } else {
            log.warn("Unexpected message type {} received, closing the connection", msgType);
            ctx.close();
        }
    }

    //called when tcpRole=Server and we have been added to the pipeline by the ReplicationServer
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        this.channelHandlerContext = ctx;
        if (req != null) {// this is the request in the constructor
            processRequest();
        }
    }

    public void shutdown() {
        channelHandlerContext.close();
    }

    private void processRequest() {
        if (dataHandlingFuture != null) {
            dataHandlingFuture.cancel(true);
        }
        if (req.hasStartTxId()) {
            nextTxToSend = req.getStartTxId();
        } else {
            log.info("The slave did not provide a startTxId, starting from 0");
            nextTxToSend = 0;
        }
        goToNextFile();

    }

    void goToNextFile() {
        currentFile = replMaster.getFile(nextTxToSend);
        if (currentFile == null) {
            log.warn("next TX to send {} is in the future, checking back in 5 seconds", nextTxToSend);
            workerGroup.schedule(() -> goToNextFile(), 5, TimeUnit.SECONDS);
            return;
        }

        if (nextTxToSend < currentFile.getFirstId()) {
            log.warn("Requested start from {} but first available transaction is {}. Replaying from there",
                    nextTxToSend, currentFile.getFirstId());
            nextTxToSend = currentFile.getFirstId();
        } else if (nextTxToSend > currentFile.getFirstId()) {
            // start from the middle of the file, write first the metadata
            Iterator<ByteBuffer> it = currentFile.metadataIterator();
            while (it.hasNext()) {
                ByteBuffer buf = it.next();
                long txId = buf.getLong(4);
                if (txId >= nextTxToSend) {
                    break;
                }
                log.debug("Sending metadata TX{} length: {} size inside: {}", txId, buf.remaining(),
                        buf.getInt(0) & 0xFFFFFF);
                ByteBuf bb = Unpooled.wrappedBuffer(buf);
                channelHandlerContext.writeAndFlush(bb);
            }
        }
        fileTail = null;
        sendMoreData();
    }

    void sendMoreData() {
        if(!channelHandlerContext.channel().isActive()) {
            return;
        }
        if (fileTail == null) {
            fileTail = currentFile.tail(nextTxToSend);
        } else {
            currentFile.getNewData(fileTail);
        }

        log.trace("nextTxToSend: {}, FileTail: {} ", nextTxToSend, fileTail);
        if (fileTail.nextTxId == nextTxToSend) {// no more data available
            if (fileTail.eof) { // file, full, go to next file
                goToNextFile();
            } else { // check back in 200 millisec
                workerGroup.schedule(() -> sendMoreData(), 200, TimeUnit.MILLISECONDS);
            }
        } else {// got some data, send it and check back for more
            ByteBuf buf = Unpooled.wrappedBuffer(fileTail.buf);
            dataHandlingFuture = channelHandlerContext.writeAndFlush(buf).addListener(a -> {
                fileTail.buf.position(fileTail.buf.limit());
                nextTxToSend = fileTail.nextTxId;
                sendMoreData();
            });
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        log.warn("Caught exception {}", cause.getMessage());
    }

    /**
     * this is called when the TCP connection is established, only when we are working as TCP client
     * in the other case the ReplicationServer adds us to the pipeline after the connection is estabilished)
     * <p>
     * Send a Wakeup message
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        log.debug("Connection {} opened, sending a wakeup message", ctx.channel().remoteAddress());
        Wakeup wp = Wakeup.newBuilder().setYamcsInstance(slaveServer.instance).build();
        ctx.writeAndFlush(protoToNetty(MessageType.WAKEUP, wp));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.debug("Connection {} closed", ctx.channel().remoteAddress());
        super.channelInactive(ctx);
        if (dataHandlingFuture != null) {
            dataHandlingFuture.cancel(true);
        }
    }
}