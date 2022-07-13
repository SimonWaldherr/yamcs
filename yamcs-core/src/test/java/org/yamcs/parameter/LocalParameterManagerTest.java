package org.yamcs.parameter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.yamcs.YConfiguration;
import org.yamcs.protobuf.Yamcs;
import org.yamcs.protobuf.Yamcs.NamedObjectId;
import org.yamcs.utils.ValueUtility;
import org.yamcs.xtce.AggregateParameterType;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.XtceDb;
import org.yamcs.xtceproc.XtceDbFactory;

public class LocalParameterManagerTest {
    XtceDb xtceDb;
    MyParamConsumer consumer;
    LocalParameterManager localParamMgr;
    Parameter p1, p2, p4, p7;

    @BeforeClass
    static public void setupTime() {
        YConfiguration.setupTest(null);
        XtceDbFactory.reset();

    }

    @Before
    public void beforeTest() throws Exception {
        localParamMgr = new LocalParameterManager();
        localParamMgr.init("test", YConfiguration.emptyConfig());
        xtceDb = XtceDbFactory.createInstanceByConfig("refmdb");
        consumer = new MyParamConsumer();
        localParamMgr.init(xtceDb);
        localParamMgr.setParameterListener(consumer);

        p1 = xtceDb.getParameter("/REFMDB/SUBSYS1/LocalPara1");
        p2 = localParamMgr.getParameter(NamedObjectId.newBuilder().setName("/REFMDB/SUBSYS1/LocalPara2").build());

        p4 = xtceDb.getParameter("/REFMDB/SUBSYS1/LocalParaWithInitialValue4");
        p7 = xtceDb.getParameter("/REFMDB/SUBSYS1/LocalParaWithInitialValue7");

        assertNotNull(p1);
        assertNotNull(p2);
    }

    @Test
    public void test() throws Exception {
        assertFalse(
                localParamMgr.canProvide(NamedObjectId.newBuilder().setName("/REFMDB/SUBSYS1/FloatPara11_2").build()));
        localParamMgr.startProviding(p1);

        ParameterValue pv1 = new ParameterValue(p1);
        pv1.setEngineeringValue(ValueUtility.getUint32Value(3));
        ParameterValue pv2 = new ParameterValue(p2);
        pv2.setEngineeringValue(ValueUtility.getDoubleValue(2.72));

        List<ParameterValue> pvList = Arrays.asList(pv1, pv2);

        localParamMgr.updateParameters(pvList);
        Collection<ParameterValue> pvs = consumer.received.poll(5, TimeUnit.SECONDS);
        assertNotNull(pvs);

        assertEquals(1, pvs.size());
        ParameterValue pv = pvs.iterator().next();
        assertEquals(p1, pv.getParameter());

        localParamMgr.startProvidingAll();

        localParamMgr.updateParameters(pvList);
        pvs = consumer.received.poll(5, TimeUnit.SECONDS);
        assertEquals(2, pvs.size());

        localParamMgr.stopProviding(p1);
        localParamMgr.updateParameters(pvList);
        pvs = consumer.received.poll(5, TimeUnit.SECONDS);
        assertEquals(1, pvs.size());
        pv = pvs.iterator().next();
        assertEquals(p2, pv.getParameter());

        localParamMgr.stopProviding(p2);
        localParamMgr.updateParameters(pvList);
        pvs = consumer.received.poll(2, TimeUnit.SECONDS);
        assertNull(pvs);
    }

    @Test
    public void testTypeConversion() throws Exception {
        localParamMgr.startProviding(p2);
        ParameterValue pv2 = new ParameterValue(p2);
        pv2.setEngineeringValue(ValueUtility.getUint32Value(3));
        localParamMgr.updateParameters(Arrays.asList(pv2));

        List<ParameterValue> pvs = consumer.received.poll(5, TimeUnit.SECONDS);
        assertEquals(3.0, pvs.get(0).getEngValue().getFloatValue(), 1e-5);
    }

    @Test
    public void testTypeConversion2() throws Exception {
        localParamMgr.startProviding(p4);
        ParameterValue pv4 = new ParameterValue(p4);
        AggregateParameterType p4type = (AggregateParameterType) p4.getParameterType();
        AggregateValue sentv = new AggregateValue(p4type.getMemberNames());
        sentv.setMemberValue("member1", ValueUtility.getSint64Value(32)); // will be converted to UINT32
        sentv.setMemberValue("member2", ValueUtility.getSint64Value(10)); // will be converted to FLOAT
        pv4.setEngineeringValue(sentv);
        localParamMgr.updateParameters(Arrays.asList(pv4));

        List<ParameterValue> pvs = consumer.received.poll(5, TimeUnit.SECONDS);
        assertEquals(1, pvs.size());
        AggregateValue rcvd = (AggregateValue) pvs.get(0).getEngValue();
        assertEquals(32, rcvd.getMemberValue("member1").getUint32Value());
        assertEquals(10, rcvd.getMemberValue("member2").getFloatValue(), 1e-5);
    }

    @Test
    public void testTypeConversion7() throws Exception {
        localParamMgr.startProviding(p7);
        ParameterValue pv7 = new ParameterValue(p7);
        ArrayValue sentv = new ArrayValue(new int[] { 2 }, Yamcs.Value.Type.SINT32);
        sentv.setElementValue(0, ValueUtility.getSint32Value(1));// will be converted to FLOAT
        sentv.setElementValue(1, ValueUtility.getSint32Value(2));// will be converted to FLOAT
        pv7.setEngineeringValue(sentv);
        localParamMgr.updateParameters(Arrays.asList(pv7));

        List<ParameterValue> pvs = consumer.received.poll(5, TimeUnit.SECONDS);
        assertEquals(1, pvs.size());
        ArrayValue rcvd = (ArrayValue) pvs.get(0).getEngValue();
        assertEquals(1, rcvd.getElementValue(0).getFloatValue(), 1e-5);
        assertEquals(2, rcvd.getElementValue(1).getFloatValue(), 1e-5);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidConversion1() throws Exception {
        localParamMgr.startProviding(p1);
        ParameterValue pv1 = new ParameterValue(p1);
        pv1.setEngineeringValue(ValueUtility.getUint64Value(Integer.MAX_VALUE * 2 + 1)); // out of range for UINT32
        localParamMgr.updateParameters(Arrays.asList(pv1));
    }

    class MyParamConsumer implements ParameterListener {
        BlockingQueue<List<ParameterValue>> received = new LinkedBlockingQueue<>();

        @Override
        public void update(Collection<ParameterValue> params) {
            try {
                received.put(new ArrayList<>(params));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }
    }
}
