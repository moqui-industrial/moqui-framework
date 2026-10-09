import org.moqui.context.ExecutionContext

final class A2ATestSupport {
    static final String USERNAME = 'john.doe'
    static final String PASSWORD = 'moqui'

    static void ensureJohnDoe(ExecutionContext ec) {
        boolean disabled = ec.artifactExecution.disableAuthz()
        try {
            ec.transaction.runUseOrBegin(null, 'A2A john.doe fixture failed') {
                ensureA2ASeed(ec)
                def user = ec.entity.find('moqui.security.UserAccount').condition('username', USERNAME)
                        .disableAuthz().useCache(false).one()
                if (user == null) {
                    ec.entity.makeValue('moqui.security.UserAccount')
                            .setSequencedIdPrimary()
                            .set('username', USERNAME)
                            .set('userFullName', 'John Doe')
                            .set('emailAddress', 'john.doe@moqui.org')
                            .set('locale', 'en_US')
                            .set('timeZone', 'US/Central')
                            .set('disabled', 'N')
                            .set('requirePasswordChange', 'N')
                            .set('currentPassword', '16ac58bbfa332c1c55bd98b53e60720bfa90d394')
                            .set('passwordHashType', 'SHA')
                            .create()
                    user = ec.entity.find('moqui.security.UserAccount').condition('username', USERNAME)
                            .disableAuthz().useCache(false).one()
                }
                if (user != null) {
                    user.setAll([userFullName: 'John Doe', emailAddress: 'john.doe@moqui.org', locale: 'en_US',
                            timeZone: 'US/Central', disabled: 'N', requirePasswordChange: 'N',
                            currentPassword: '16ac58bbfa332c1c55bd98b53e60720bfa90d394', passwordHashType: 'SHA'])
                    user.update()
                    ensureGroupMember(ec, user.userId as String, 'ALL_USERS')
                    ensureGroupMember(ec, user.userId as String, 'ADMIN')
                    ensureUserPermission(ec, 'ADMIN', 'LlmGateway', 'LLM Gateway Servlet Access')
                }
            }
            ec.message.clearErrors()
        } finally {
            if (!disabled) ec.artifactExecution.enableAuthz()
        }
    }

    private static void ensureGroupMember(ExecutionContext ec, String userId, String userGroupId) {
        def group = ec.entity.find('moqui.security.UserGroup').condition('userGroupId', userGroupId)
                .disableAuthz().one()
        if (group == null) {
            ec.entity.makeValue('moqui.security.UserGroup')
                    .setAll([userGroupId: userGroupId, description: userGroupId]).create()
        }
        def existing = ec.entity.find('moqui.security.UserGroupMember')
                .condition([userId: userId, userGroupId: userGroupId]).disableAuthz().one()
        if (existing == null) {
            ec.entity.makeValue('moqui.security.UserGroupMember')
                    .setAll([userId: userId, userGroupId: userGroupId, fromDate: ec.user.nowTimestamp])
                    .create()
        }
    }

    private static void ensureA2ASeed(ExecutionContext ec) {
        ensureEnumType(ec, 'A2ATaskStatus', 'A2A Task Status')
        ensureEnum(ec, 'A2AtsUnspecified', 'A2ATaskStatus', 'TASK_STATE_UNSPECIFIED', 'Unspecified')
        ensureEnum(ec, 'A2AtsSubmitted', 'A2ATaskStatus', 'TASK_STATE_SUBMITTED', 'Submitted')
        ensureEnum(ec, 'A2AtsWorking', 'A2ATaskStatus', 'TASK_STATE_WORKING', 'Working')
        ensureEnum(ec, 'A2AtsCompleted', 'A2ATaskStatus', 'TASK_STATE_COMPLETED', 'Completed')
        ensureEnum(ec, 'A2AtsFailed', 'A2ATaskStatus', 'TASK_STATE_FAILED', 'Failed')
        ensureEnum(ec, 'A2AtsCanceled', 'A2ATaskStatus', 'TASK_STATE_CANCELED', 'Canceled')
        ensureEnum(ec, 'A2AtsInputRequired', 'A2ATaskStatus', 'TASK_STATE_INPUT_REQUIRED', 'Input Required')
        ensureEnum(ec, 'A2AtsRejected', 'A2ATaskStatus', 'TASK_STATE_REJECTED', 'Rejected')
        ensureEnum(ec, 'A2AtsAuthRequired', 'A2ATaskStatus', 'TASK_STATE_AUTH_REQUIRED', 'Authentication Required')
        ensureEnumType(ec, 'A2APartType', 'A2A Part Type')
        ensureEnum(ec, 'A2APrtText', 'A2APartType', 'text', 'Text Part')
        ensureEnum(ec, 'A2APrtRaw', 'A2APartType', 'raw', 'Raw Bytes Part')
        ensureEnum(ec, 'A2APrtUrl', 'A2APartType', 'url', 'URL Part')
        ensureEnum(ec, 'A2APrtData', 'A2APartType', 'data', 'Data Part')
        ensureEnumType(ec, 'A2ATaskEventType', 'A2A Task Event Type')
        ensureEnum(ec, 'A2AEvtStatus', 'A2ATaskEventType', 'status-update', 'Task Status Update')
        ensureEnum(ec, 'A2AEvtArtifact', 'A2ATaskEventType', 'artifact-update', 'Task Artifact Update')
    }

    private static void ensureUserPermission(ExecutionContext ec, String userGroupId, String userPermissionId,
            String description) {
        def permission = ec.entity.find('moqui.security.UserPermission')
                .condition('userPermissionId', userPermissionId).disableAuthz().useCache(false).one()
        if (permission == null) {
            ec.entity.makeValue('moqui.security.UserPermission')
                    .setAll([userPermissionId: userPermissionId, description: description]).create()
        }
        def groupPermission = ec.entity.find('moqui.security.UserGroupPermission')
                .condition([userGroupId: userGroupId, userPermissionId: userPermissionId])
                .disableAuthz().useCache(false).one()
        if (groupPermission == null) {
            ec.entity.makeValue('moqui.security.UserGroupPermission')
                    .setAll([userGroupId: userGroupId, userPermissionId: userPermissionId, fromDate: ec.user.nowTimestamp])
                    .create()
        }
    }

    private static void ensureEnumType(ExecutionContext ec, String enumTypeId, String description) {
        if (ec.entity.find('moqui.basic.EnumerationType').condition('enumTypeId', enumTypeId)
                .disableAuthz().useCache(false).one() == null) {
            ec.entity.makeValue('moqui.basic.EnumerationType')
                    .setAll([enumTypeId: enumTypeId, description: description]).create()
        }
    }

    private static void ensureEnum(ExecutionContext ec, String enumId, String enumTypeId, String enumCode,
            String description) {
        def enumeration = ec.entity.find('moqui.basic.Enumeration').condition('enumId', enumId)
                .disableAuthz().useCache(false).one()
        if (enumeration == null) {
            ec.entity.makeValue('moqui.basic.Enumeration')
                    .setAll([enumId: enumId, enumTypeId: enumTypeId, enumCode: enumCode, description: description])
                    .create()
        } else {
            enumeration.setAll([enumTypeId: enumTypeId, enumCode: enumCode, description: description])
            enumeration.update()
        }
    }
}
