package org.zstack.storage.ceph.backup;

import org.zstack.header.message.APIParam;
import org.zstack.header.storage.backup.APIAddBackupStorageMsg;
import org.zstack.storage.ceph.CephConstants;

import java.util.List;

/**
 * Created by frank on 7/27/2015.
 */
public class APIAddCephBackupStorageMsg extends APIAddBackupStorageMsg {
    @APIParam(nonempty = false)
    private List<String> monUrls;
    @APIParam(required = false, maxLength = 255)
    private String poolName;

    public String getPoolName() {
        return poolName;
    }

    public void setPoolName(String poolName) {
        this.poolName = poolName;
    }

    public List<String> getMonUrls() {
        return monUrls;
    }

    public void setMonUrls(List<String> monUrls) {
        this.monUrls = monUrls;
    }

    @Override
    public String getType() {
        return CephConstants.CEPH_BACKUP_STORAGE_TYPE;
    }
}
