package org.zstack.storage.ceph.backup;

import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.zstack.core.CoreGlobalProperty;
import org.zstack.core.ansible.AnsibleGlobalProperty;
import org.zstack.core.ansible.AnsibleRunner;
import org.zstack.core.ansible.SshFileMd5Checker;
import org.zstack.core.db.DatabaseFacade;
import org.zstack.core.thread.ChainTask;
import org.zstack.core.thread.SyncTaskChain;
import org.zstack.core.thread.ThreadFacade;
import org.zstack.core.workflow.FlowChainBuilder;
import org.zstack.core.workflow.ShareFlow;
import org.zstack.header.core.Completion;
import org.zstack.header.core.ReturnValueCompletion;
import org.zstack.header.core.workflow.*;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.rest.JsonAsyncRESTCallback;
import org.zstack.header.rest.RESTFacade;
import org.zstack.storage.ceph.CephGlobalProperty;
import org.zstack.storage.ceph.CephMonAO;
import org.zstack.storage.ceph.CephMonBase;
import org.zstack.storage.ceph.MonStatus;
import org.zstack.utils.Utils;
import org.zstack.utils.logging.CLogger;
import org.zstack.utils.path.PathUtil;

import java.util.Map;

/**
 * Created by frank on 7/27/2015.
 */
@Configurable(preConstruction = true, autowire = Autowire.BY_TYPE, dependencyCheck = true)
public class CephBackupStorageMonBase extends CephMonBase {
    private static final CLogger logger = Utils.getLogger(CephBackupStorageMonBase.class);

    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private RESTFacade restf;
    @Autowired
    private ThreadFacade thdf;

    private String syncId;

    public static final String ECHO_PATH = "/ceph/backupstorage/echo";
    public static final String PING_PATH = "/ceph/backupstorage/ping";

    public static class AgentCmd {
        public String monUuid;
        public String backupStorageUuid;
    }

    public static class AgentRsp {
        public boolean success;
        public String error;
    }

    public static class PingCmd extends AgentCmd {
    }

    public static class PingRsp extends AgentRsp {
        public boolean operationFailure;
    }

    public CephBackupStorageMonBase(CephMonAO self) {
        super(self);
        syncId = String.format("ceph-backup-storage-mon-%s", getSelf().getUuid());
    }

    public CephBackupStorageMonVO getSelf() {
        return (CephBackupStorageMonVO) self;
    }

    public void changeStatus(MonStatus status) {
        if (self.getStatus() == status) {
            return;
        }

        MonStatus oldStatus = self.getStatus();
        self.setStatus(status);
        self = dbf.updateAndRefresh(self);
        logger.debug(String.format("ceph backup storage mon[uuid:%s] changed status from %s to %s",
                self.getUuid(), oldStatus, status));
    }

    @Override
    public void connect(final Completion completion) {
        thdf.chainSubmit(new ChainTask() {
            @Override
            public String getSyncSignature() {
                return syncId;
            }

            @Override
            public void run(final SyncTaskChain chain) {
                doConnect(new Completion(completion, chain) {
                    @Override
                    public void success() {
                        completion.success();
                        chain.next();
                    }

                    @Override
                    public void fail(ErrorCode errorCode) {
                        completion.fail(errorCode);
                        chain.next();
                    }
                });
            }

            @Override
            public String getName() {
                return String.format("connect-ceph-backup-storage-mon-%s", self.getUuid());
            }
        });
    }

    private void doConnect(final Completion completion) {
        changeStatus(MonStatus.Connecting);

        final FlowChain chain = FlowChainBuilder.newShareFlowChain();
        chain.setName(String.format("connect-mon-%s-ceph-backup-storage-%s", self.getHostname(), getSelf().getBackupStorageUuid()));
        chain.allowEmptyFlow();
        chain.then(new ShareFlow() {
            @Override
            public void setup() {
                if (!CoreGlobalProperty.UNIT_TEST_ON) {
                    flow(new NoRollbackFlow() {
                        String __name__ = "check-tools";

                        @Override
                        public void run(FlowTrigger trigger, Map data) {
                            checkTools();
                            trigger.next();
                        }
                    });

                    flow(new NoRollbackFlow() {
                        String __name__ = "deploy-agent";

                        @Override
                        public void run(final FlowTrigger trigger, Map data) {
                            SshFileMd5Checker checker = new SshFileMd5Checker();
                            checker.setTargetIp(getSelf().getHostname());
                            checker.setUsername(getSelf().getSshUsername());
                            checker.setPassword(getSelf().getSshPassword());
                            checker.addSrcDestPair(SshFileMd5Checker.ZSTACKLIB_SRC_PATH, String.format("/var/lib/zstack/cephb/%s", AnsibleGlobalProperty.ZSTACKLIB_PACKAGE_NAME));
                            checker.addSrcDestPair(PathUtil.findFileOnClassPath(String.format("ansible/cephb/%s", CephGlobalProperty.BACKUP_STORAGE_PACKAGE_NAME), true).getAbsolutePath(),
                                    String.format("/var/lib/zstack/cephb/%s", CephGlobalProperty.BACKUP_STORAGE_PACKAGE_NAME));
                            AnsibleRunner runner = new AnsibleRunner();
                            runner.installChecker(checker);
                            runner.setPassword(getSelf().getSshPassword());
                            runner.setUsername(getSelf().getSshUsername());
                            runner.setTargetIp(getSelf().getHostname());
                            runner.setSshPort(getSelf().getSshPort());
                            runner.setAgentPort(CephGlobalProperty.BACKUP_STORAGE_AGENT_PORT);
                            runner.setPlayBookName(CephGlobalProperty.BACKUP_STORAGE_PLAYBOOK_NAME);
                            runner.putArgument("pkg_cephbagent", CephGlobalProperty.BACKUP_STORAGE_PACKAGE_NAME);
                            runner.run(new Completion(trigger) {
                                @Override
                                public void success() {
                                    trigger.next();
                                }

                                @Override
                                public void fail(ErrorCode errorCode) {
                                    trigger.fail(errorCode);
                                }
                            });
                        }
                    });

                    flow(new NoRollbackFlow() {
                        String __name__ = "echo-agent";

                        @Override
                        public void run(final FlowTrigger trigger, Map data) {
                            restf.echo(String.format("http://%s:%s%s", getSelf().getHostname(),
                                    CephGlobalProperty.BACKUP_STORAGE_AGENT_PORT, ECHO_PATH), new Completion(trigger) {
                                @Override
                                public void success() {
                                    trigger.next();
                                }

                                @Override
                                public void fail(ErrorCode errorCode) {
                                    trigger.fail(errorCode);
                                }
                            });
                        }
                    });
                }

                done(new FlowDoneHandler(completion) {
                    @Override
                    public void handle(Map data) {
                        changeStatus(MonStatus.Connected);
                        completion.success();
                    }
                });

                error(new FlowErrorHandler(completion) {
                    @Override
                    public void handle(ErrorCode errCode, Map data) {
                        changeStatus(MonStatus.Disconnected);
                        completion.fail(errCode);
                    }
                });
            }
        }).start();
    }

    private void httpCall(String path, AgentCmd cmd, final ReturnValueCompletion<AgentRsp> completion) {
        httpCall(path, cmd, AgentRsp.class, completion);
    }

    private <T> void httpCall(String path, AgentCmd cmd, final Class<T> rspClass, final ReturnValueCompletion<T> completion) {
        restf.asyncJsonPost(String.format("http://%s:%s%s", self.getHostname(), CephGlobalProperty.PRIMARY_STORAGE_AGENT_PORT, path),
                cmd, new JsonAsyncRESTCallback<T>(completion) {
                    @Override
                    public void fail(ErrorCode err) {
                        completion.fail(err);
                    }

                    @Override
                    public void success(T ret) {
                        completion.success(ret);
                    }

                    @Override
                    public Class<T> getReturnClass() {
                        return rspClass;
                    }
                });
    }

    @Override
    public void ping(final ReturnValueCompletion<PingResult> completion) {
        thdf.chainSubmit(new ChainTask() {
            @Override
            public String getSyncSignature() {
                return syncId;
            }

            @Override
            public void run(final SyncTaskChain chain) {
                doPing(new ReturnValueCompletion<PingResult>(completion, chain) {
                    @Override
                    public void success(PingResult ret) {
                        completion.success(ret);
                        chain.next();
                    }

                    @Override
                    public void fail(ErrorCode errorCode) {
                        completion.fail(errorCode);
                        chain.next();
                    }
                });
            }

            @Override
            public String getName() {
                return String.format("ping-ceph-backup-storage-mon-%s", self.getUuid());
            }
        });
    }

    public void doPing(final ReturnValueCompletion<PingResult> completion) {
        PingCmd cmd = new PingCmd();
        cmd.monUuid = getSelf().getUuid();
        cmd.backupStorageUuid = getSelf().getBackupStorageUuid();

        httpCall(PING_PATH, cmd, PingRsp.class, new ReturnValueCompletion<PingRsp>(completion) {
            @Override
            public void success(PingRsp rsp) {
                PingResult res = new PingResult();
                if (rsp.success) {
                    res.success = true;
                } else {
                    res.success = false;
                    res.error = rsp.error;
                    res.operationFailure = rsp.operationFailure;
                }

                completion.success(res);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                completion.fail(errorCode);
            }
        });
    }
}
