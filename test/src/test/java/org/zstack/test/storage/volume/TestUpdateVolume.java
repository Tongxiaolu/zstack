package org.zstack.test.storage.volume;

import junit.framework.Assert;
import org.junit.Before;
import org.junit.Test;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.componentloader.ComponentLoader;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.header.configuration.DiskOfferingInventory;
import org.zstack.header.volume.VolumeInventory;
import org.zstack.test.Api;
import org.zstack.test.ApiSenderException;
import org.zstack.test.BeanConstructor;
import org.zstack.test.DBUtil;
import org.zstack.utils.Utils;
import org.zstack.utils.data.SizeUnit;
import org.zstack.utils.logging.CLogger;

public class TestUpdateVolume {
    CLogger logger = Utils.getLogger(TestUpdateVolume.class);
    Api api;
    ComponentLoader loader;
    DatabaseFacade dbf;
    CloudBus bus;

    @Before
    public void setUp() throws Exception {
        DBUtil.reDeployDB();
        BeanConstructor con = new BeanConstructor();
        /* This loads spring application context */
        loader = con.addXml("PortalForUnitTest.xml").addXml("Simulator.xml").addXml("ZoneManager.xml")
                .addXml("PrimaryStorageManager.xml").addXml("ConfigurationManager.xml").addXml("VolumeManager.xml").addXml("AccountManager.xml").build();
        dbf = loader.getComponent(DatabaseFacade.class);
        bus = loader.getComponent(CloudBus.class);
        api = new Api();
        api.startServer();
    }

    @Test
    public void test() throws ApiSenderException {
        DiskOfferingInventory dinv = new DiskOfferingInventory();
        dinv.setDiskSize(SizeUnit.GIGABYTE.toByte(10));
        dinv.setName("Test");
        dinv.setDescription("Test");
        dinv = api.addDiskOffering(dinv);

        VolumeInventory vinv = api.createDataVolume("TestData", dinv.getUuid());
        vinv.setName("1");
        vinv.setDescription("xxx");
        vinv = api.updateVolume(vinv);
        Assert.assertEquals("1", vinv.getName());
        Assert.assertEquals("xxx", vinv.getDescription());
    }
}
