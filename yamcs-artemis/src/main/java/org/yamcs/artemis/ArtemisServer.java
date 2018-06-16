package org.yamcs.artemis;

import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.spi.core.security.ActiveMQSecurityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.security.SecurityStore;
import org.yamcs.utils.YObjectLoader;

import com.google.common.util.concurrent.AbstractService;

/**
 * Server wide service that initialises and starts the artemis/hornetq server
 * 
 * 
 * @author nm
 *
 */
public class ArtemisServer extends AbstractService {
    static Logger log = LoggerFactory.getLogger(ArtemisServer.class.getName());

    static EmbeddedActiveMQ artemisServer;

    public ArtemisServer() throws ConfigurationException {
        // divert artemis logging
        System.setProperty("org.jboss.logging.provider", "slf4j");
        System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.Jdk14Logger");
    }

    public static EmbeddedActiveMQ setupArtemis() throws Exception {
        if (artemisServer != null) {
            throw new ConfigurationException("This service cannot be instantiated more than once");
        }
        // load optional configuration file name for ActiveMQ Artemis,
        // otherwise default will be artemis.xml
        String artemisConfigFile = "artemis.xml";
        YConfiguration c = YConfiguration.getConfiguration("yamcs");
        if (c.containsKey("artemisConfigFile")) {
            artemisConfigFile = c.getString("artemisConfigFile");
        }

        EmbeddedActiveMQ artemisServer = new EmbeddedActiveMQ();
        SecurityStore security = SecurityStore.getInstance();
        if (security.isEnabled()) {
            YConfiguration conf = YConfiguration.getConfiguration("security");
            if (conf.containsKey("artemisAuthModule")) {
                ActiveMQSecurityManager secmgr = YObjectLoader.loadObject(conf.getMap("artemisAuthModule"));
                artemisServer.setSecurityManager(secmgr);
            } else {
                throw new ConfigurationException(
                        "Security is enabled but there is no artemisAuthModule configured in security.yaml");
            }
        }
        if (artemisConfigFile != null) {
            artemisServer.setConfigResourcePath(artemisConfigFile);
        }
        artemisServer.start();

        return artemisServer;
    }

    @Override
    protected void doStart() {
        try {
            artemisServer = ArtemisServer.setupArtemis();
            notifyStarted();
        } catch (Exception e) {
            notifyFailed(e);
        }
    }

    @Override
    protected void doStop() {
        try {
            artemisServer.stop();
            notifyStopped();
        } catch (Exception e) {
            log.error("Failed to close the yamcs session", e);
        }
    }
}
