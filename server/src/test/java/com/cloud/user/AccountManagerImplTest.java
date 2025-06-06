// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.acl.Role;
import org.apache.cloudstack.acl.RoleService;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.command.admin.account.UpdateAccountCmd;
import org.apache.cloudstack.api.command.admin.user.DeleteUserCmd;
import org.apache.cloudstack.api.command.admin.user.GetUserKeysCmd;
import org.apache.cloudstack.api.command.admin.user.UpdateUserCmd;
import org.apache.cloudstack.api.response.UserTwoFactorAuthenticationSetupResponse;
import org.apache.cloudstack.auth.UserAuthenticator;
import org.apache.cloudstack.auth.UserAuthenticator.ActionOnFailedAuthentication;
import org.apache.cloudstack.auth.UserTwoFactorAuthenticator;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.resourcedetail.UserDetailVO;
import org.apache.cloudstack.webhook.WebhookHelper;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;

import com.cloud.acl.DomainChecker;
import com.cloud.api.auth.SetupUserTwoFactorAuthenticationCmd;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.event.ActionEventUtils;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectAccountVO;
import com.cloud.user.Account.State;
import com.cloud.utils.Pair;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmManagerImpl;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.snapshot.VMSnapshotVO;

@RunWith(MockitoJUnitRunner.class)
public class AccountManagerImplTest extends AccountManagetImplTestBase {

    @Mock
    private UserVmManagerImpl _vmMgr;
    @Mock
    private AccountVO callingAccount;
    @Mock
    private DomainChecker domainChecker;
    @Mock
    private AccountService accountService;
    @Mock
    private GetUserKeysCmd _listkeyscmd;
    @Mock
    private User _user;
    @Mock
    private UserAccountVO userAccountVO;

    @Mock
    private UpdateUserCmd UpdateUserCmdMock;

    @Mock
    private UpdateAccountCmd UpdateAccountCmdMock;

    private long userVoIdMock = 111l;
    @Mock
    private UserVO userVoMock;

    private long accountMockId = 100l;

    @Mock
    private Account accountMock;

    @Mock
    private DomainVO domainVoMock;

    @Mock
    private AccountVO accountVoMock;
    @Mock
    private AccountVO _systemAccount;

    @Mock
    private ProjectAccountVO projectAccountVO;
    @Mock
    private Project project;

    @Mock
    PasswordPolicyImpl passwordPolicyMock;

    @Mock
    ConfigKey<Boolean> enableUserTwoFactorAuthenticationMock;

    @Mock
    ConfigKey<Boolean> allowOperationsOnUsersInSameAccountMock;
    @Mock
    RoleService roleService;

    @Before
    public void setUp() throws Exception {
        enableUserTwoFactorAuthenticationMock = Mockito.mock(ConfigKey.class);
        accountManagerImpl.enableUserTwoFactorAuthentication = enableUserTwoFactorAuthenticationMock;

        allowOperationsOnUsersInSameAccountMock = Mockito.mock(ConfigKey.class);
        accountManagerImpl.allowOperationsOnUsersInSameAccount = allowOperationsOnUsersInSameAccountMock;
    }

    @Before
    public void beforeTest() {
        Mockito.doReturn(accountMockId).when(accountMock).getId();
        Mockito.doReturn(accountMock).when(accountManagerImpl).getCurrentCallingAccount();

        Mockito.doReturn(accountMockId).when(userVoMock).getAccountId();

        Mockito.doReturn(userVoIdMock).when(userVoMock).getId();

        Mockito.lenient().doNothing().when(accountManagerImpl).checkRoleEscalation(accountMock, accountMock);
    }

    @Test
    public void disableAccountNotexisting() throws ConcurrentOperationException, ResourceUnavailableException {
        Mockito.when(_accountDao.findById(42l)).thenReturn(null);
        Assert.assertTrue(accountManagerImpl.disableAccount(42));
    }

    @Test
    public void disableAccountDisabled() throws ConcurrentOperationException, ResourceUnavailableException {
        AccountVO disabledAccount = new AccountVO();
        disabledAccount.setState(State.DISABLED);
        Mockito.when(_accountDao.findById(42l)).thenReturn(disabledAccount);
        Assert.assertTrue(accountManagerImpl.disableAccount(42));
    }

    @Test
    public void disableAccount() throws ConcurrentOperationException, ResourceUnavailableException {
        AccountVO account = new AccountVO();
        account.setState(State.ENABLED);
        Mockito.when(_accountDao.findById(42l)).thenReturn(account);
        Mockito.when(_accountDao.createForUpdate()).thenReturn(new AccountVO());
        Mockito.when(_accountDao.update(Mockito.eq(42l), Mockito.any(AccountVO.class))).thenReturn(true);
        Mockito.when(_vmDao.listByAccountId(42l)).thenReturn(Arrays.asList(Mockito.mock(VMInstanceVO.class)));
        Assert.assertTrue(accountManagerImpl.disableAccount(42));
        Mockito.verify(_accountDao, Mockito.atLeastOnce()).update(Mockito.eq(42l), Mockito.any(AccountVO.class));
    }

    @Test
    public void deleteUserAccount() {
        AccountVO account = new AccountVO();
        account.setId(42l);
        DomainVO domain = new DomainVO();
        Mockito.when(_accountDao.findById(42l)).thenReturn(account);
        Mockito.doNothing().when(accountManagerImpl).checkAccess(Mockito.any(Account.class), Mockito.isNull(), Mockito.anyBoolean(), Mockito.any(Account.class));
        Mockito.when(_accountDao.remove(42l)).thenReturn(true);
        Mockito.when(_configMgr.releaseAccountSpecificVirtualRanges(account)).thenReturn(true);
        Mockito.lenient().when(_domainMgr.getDomain(Mockito.anyLong())).thenReturn(domain);
        Mockito.lenient().when(securityChecker.checkAccess(Mockito.any(Account.class), Mockito.any(Domain.class))).thenReturn(true);
        Mockito.when(_vmSnapshotDao.listByAccountId(Mockito.anyLong())).thenReturn(new ArrayList<VMSnapshotVO>());
        Mockito.when(_autoscaleMgr.deleteAutoScaleVmGroupsByAccount(account)).thenReturn(true);

        List<SSHKeyPairVO> sshkeyList = new ArrayList<SSHKeyPairVO>();
        SSHKeyPairVO sshkey = new SSHKeyPairVO();
        sshkey.setId(1l);
        sshkeyList.add(sshkey);
        Mockito.when(_sshKeyPairDao.listKeyPairs(Mockito.anyLong(), Mockito.anyLong())).thenReturn(sshkeyList);
        Mockito.when(_sshKeyPairDao.remove(Mockito.anyLong())).thenReturn(true);
        Mockito.when(userDataDao.removeByAccountId(Mockito.anyLong())).thenReturn(222);
        Mockito.doNothing().when(accountManagerImpl).deleteWebhooksForAccount(Mockito.anyLong());
        Mockito.doNothing().when(accountManagerImpl).verifyCallerPrivilegeForUserOrAccountOperations((Account) any());

        Assert.assertTrue(accountManagerImpl.deleteUserAccount(42l));
        // assert that this was a clean delete
        Mockito.verify(_accountDao, Mockito.never()).markForCleanup(Mockito.eq(42l));
    }

    @Test
    public void deleteUserAccountCleanup() {
        AccountVO account = new AccountVO();
        account.setId(42l);
        DomainVO domain = new DomainVO();
        Mockito.when(_accountDao.findById(42l)).thenReturn(account);
        Mockito.doNothing().when(accountManagerImpl).checkAccess(Mockito.any(Account.class), Mockito.isNull(), Mockito.anyBoolean(), Mockito.any(Account.class));
        Mockito.when(_accountDao.remove(42l)).thenReturn(true);
        Mockito.when(_configMgr.releaseAccountSpecificVirtualRanges(account)).thenReturn(true);
        Mockito.when(_userVmDao.listByAccountId(42l)).thenReturn(Arrays.asList(Mockito.mock(UserVmVO.class)));
        Mockito.when(_vmMgr.expunge(Mockito.any(UserVmVO.class))).thenReturn(false);
        Mockito.lenient().when(_domainMgr.getDomain(Mockito.anyLong())).thenReturn(domain);
        Mockito.lenient().when(securityChecker.checkAccess(Mockito.any(Account.class), Mockito.any(Domain.class))).thenReturn(true);
        Mockito.doNothing().when(accountManagerImpl).deleteWebhooksForAccount(Mockito.anyLong());
        Mockito.doNothing().when(accountManagerImpl).verifyCallerPrivilegeForUserOrAccountOperations((Account) any());

        Assert.assertTrue(accountManagerImpl.deleteUserAccount(42l));
        // assert that this was NOT a clean delete
        Mockito.verify(_accountDao, Mockito.atLeastOnce()).markForCleanup(Mockito.eq(42l));
    }

    @Test (expected = InvalidParameterValueException.class)
    public void deleteUserAccountTestIfAccountIdIsEqualToCallerIdShouldThrowException() {
        try (MockedStatic<CallContext> callContextMocked = Mockito.mockStatic(CallContext.class)) {
            CallContext callContextMock = Mockito.mock(CallContext.class);
            callContextMocked.when(CallContext::current).thenReturn(callContextMock);
            long accountId = 1L;

            Mockito.doReturn(accountVoMock).when(callContextMock).getCallingAccount();
            Mockito.doReturn(accountVoMock).when(_accountDao).findById(Mockito.anyLong());
            Mockito.doReturn(domainVoMock).when(_domainDao).findById(Mockito.anyLong());
            Mockito.doReturn(1L).when(accountVoMock).getId();

            accountManagerImpl.deleteUserAccount(accountId);
        }
    }

    @Test
    public void deleteUserAccountTestIfAccountIdIsNotEqualToCallerAccountIdShouldNotThrowException() {
        try (MockedStatic<CallContext> callContextMocked = Mockito.mockStatic(CallContext.class)) {
            CallContext callContextMock = Mockito.mock(CallContext.class);
            callContextMocked.when(CallContext::current).thenReturn(callContextMock);
            long accountId = 1L;

            Mockito.doReturn(accountVoMock).when(callContextMock).getCallingAccount();
            Mockito.doReturn(accountVoMock).when(_accountDao).findById(Mockito.anyLong());
            Mockito.doReturn(2L).when(accountVoMock).getId();
            Mockito.doReturn(true).when(accountManagerImpl).isDeleteNeeded(Mockito.any(), Mockito.anyLong(), Mockito.any());
            Mockito.doReturn(new ArrayList<Long>()).when(_projectAccountDao).listAdministratedProjectIds(Mockito.anyLong());
            Mockito.doNothing().when(accountManagerImpl).verifyCallerPrivilegeForUserOrAccountOperations((Account) any());

            accountManagerImpl.deleteUserAccount(accountId);
        }
    }

    @Test (expected = InvalidParameterValueException.class)
    public void deleteUserTestIfUserIdIsEqualToCallerIdShouldThrowException() {
        try (MockedStatic<CallContext> callContextMocked = Mockito.mockStatic(CallContext.class)) {
            DeleteUserCmd cmd = Mockito.mock(DeleteUserCmd.class);
            CallContext callContextMock = Mockito.mock(CallContext.class);
            callContextMocked.when(CallContext::current).thenReturn(callContextMock);

            Mockito.doReturn(userVoMock).when(callContextMock).getCallingUser();
            Mockito.doReturn(1L).when(cmd).getId();
            Mockito.doReturn(userVoMock).when(accountManagerImpl).getValidUserVO(Mockito.anyLong());
            Mockito.doReturn(accountVoMock).when(_accountDao).findById(Mockito.anyLong());
            Mockito.doReturn(domainVoMock).when(_domainDao).findById(Mockito.anyLong());
            Mockito.doReturn(1L).when(userVoMock).getId();

            accountManagerImpl.deleteUser(cmd);
        }
    }

    @Test
    public void deleteUserTestIfUserIdIsNotEqualToCallerIdShouldNotThrowException() {
        try (MockedStatic<CallContext> callContextMocked = Mockito.mockStatic(CallContext.class)) {
            DeleteUserCmd cmd = Mockito.mock(DeleteUserCmd.class);
            CallContext callContextMock = Mockito.mock(CallContext.class);
            callContextMocked.when(CallContext::current).thenReturn(callContextMock);

            Mockito.doReturn(userVoMock).when(callContextMock).getCallingUser();
            Mockito.doReturn(1L).when(cmd).getId();
            Mockito.doReturn(userVoMock).when(accountManagerImpl).getValidUserVO(Mockito.anyLong());
            Mockito.doReturn(accountVoMock).when(_accountDao).findById(Mockito.anyLong());
            Mockito.doReturn(2L).when(userVoMock).getId();

            Mockito.doNothing().when(accountManagerImpl).checkAccountAndAccess(Mockito.any(), Mockito.any());
            Mockito.doNothing().when(accountManagerImpl).verifyCallerPrivilegeForUserOrAccountOperations(userVoMock);
            accountManagerImpl.deleteUser(cmd);
        }
    }

    @Test
    public void testAuthenticateUser() throws UnknownHostException {
        Pair<Boolean, UserAuthenticator.ActionOnFailedAuthentication> successAuthenticationPair = new Pair<>(true, null);
        Pair<Boolean, UserAuthenticator.ActionOnFailedAuthentication> failureAuthenticationPair = new Pair<>(false,
                UserAuthenticator.ActionOnFailedAuthentication.INCREMENT_INCORRECT_LOGIN_ATTEMPT_COUNT);

        UserAccountVO userAccountVO = new UserAccountVO();
        userAccountVO.setSource(User.Source.UNKNOWN);
        userAccountVO.setState(Account.State.DISABLED.toString());
        Mockito.when(userAccountDaoMock.getUserAccount("test", 1L)).thenReturn(userAccountVO);
        Mockito.when(userAuthenticator.authenticate("test", "fail", 1L, new HashMap<>())).thenReturn(failureAuthenticationPair);
        Mockito.lenient().when(userAuthenticator.authenticate("test", null, 1L, new HashMap<>())).thenReturn(successAuthenticationPair);
        Mockito.lenient().when(userAuthenticator.authenticate("test", "", 1L, new HashMap<>())).thenReturn(successAuthenticationPair);
        Mockito.when(userAuthenticator.getName()).thenReturn("test");

        //Test for incorrect password. authentication should fail
        UserAccount userAccount = accountManagerImpl.authenticateUser("test", "fail", 1L, InetAddress.getByName("127.0.0.1"), new HashMap<>());
        Assert.assertNull(userAccount);

        //Test for null password. authentication should fail
        userAccount = accountManagerImpl.authenticateUser("test", null, 1L, InetAddress.getByName("127.0.0.1"), new HashMap<>());
        Assert.assertNull(userAccount);

        //Test for empty password. authentication should fail
        userAccount = accountManagerImpl.authenticateUser("test", "", 1L, InetAddress.getByName("127.0.0.1"), new HashMap<>());
        Assert.assertNull(userAccount);

        //Verifying that the authentication method is only called when password is specified
        Mockito.verify(userAuthenticator, Mockito.times(1)).authenticate("test", "fail", 1L, new HashMap<>());
        Mockito.verify(userAuthenticator, Mockito.never()).authenticate("test", null, 1L, null);
        Mockito.verify(userAuthenticator, Mockito.never()).authenticate("test", "", 1L, null);
    }

    @Test(expected = PermissionDeniedException.class)
    public void testgetUserCmd() {
        CallContext.register(callingUser, callingAccount); // Calling account is user account i.e normal account
        Mockito.when(_listkeyscmd.getID()).thenReturn(1L);
        Mockito.when(accountManagerImpl.getActiveUser(1L)).thenReturn(userVoMock);
        Mockito.when(userAccountDaoMock.findById(1L)).thenReturn(userAccountVO);
        Mockito.when(userAccountVO.getAccountId()).thenReturn(1L);
        Mockito.lenient().when(accountManagerImpl.getAccount(Mockito.anyLong())).thenReturn(accountMock); // Queried account - admin account

        Mockito.lenient().when(callingUser.getAccountId()).thenReturn(1L);
        Mockito.lenient().when(_accountDao.findById(1L)).thenReturn(callingAccount);

        Mockito.lenient().when(accountService.isNormalUser(Mockito.anyLong())).thenReturn(Boolean.TRUE);
        Mockito.lenient().when(accountMock.getAccountId()).thenReturn(2L);

        accountManagerImpl.getKeys(_listkeyscmd);
    }

    @Test(expected = PermissionDeniedException.class)
    public void testGetUserKeysCmdDomainAdminRootAdminUser() {
        CallContext.register(callingUser, callingAccount);
        Mockito.when(_listkeyscmd.getID()).thenReturn(2L);
        Mockito.when(accountManagerImpl.getActiveUser(2L)).thenReturn(userVoMock);
        Mockito.when(userAccountDaoMock.findById(2L)).thenReturn(userAccountVO);
        Mockito.when(userAccountVO.getAccountId()).thenReturn(2L);
        Mockito.when(userDetailsDaoMock.listDetailsKeyPairs(Mockito.anyLong())).thenReturn(null);

        // Queried account - admin account
        AccountVO adminAccountMock = Mockito.mock(AccountVO.class);
        Mockito.when(_accountDao.findByIdIncludingRemoved(2L)).thenReturn(adminAccountMock);
        Mockito.lenient().when(accountService.isRootAdmin(2L)).thenReturn(true);
        Mockito.lenient().when(securityChecker.checkAccess(Mockito.any(Account.class),
                Mockito.nullable(ControlledEntity.class), Mockito.nullable(AccessType.class), Mockito.anyString())).thenReturn(true);

        // Calling account is domain admin of the ROOT domain
        Mockito.lenient().when(callingAccount.getType()).thenReturn(Account.Type.DOMAIN_ADMIN);
        Mockito.lenient().when(callingAccount.getDomainId()).thenReturn(Domain.ROOT_DOMAIN);

        Mockito.lenient().when(callingUser.getAccountId()).thenReturn(2L);
        Mockito.lenient().when(_accountDao.findById(2L)).thenReturn(callingAccount);

        Mockito.lenient().when(accountService.isDomainAdmin(Mockito.anyLong())).thenReturn(Boolean.TRUE);
        Mockito.lenient().when(accountMock.getAccountId()).thenReturn(2L);

        accountManagerImpl.getKeys(_listkeyscmd);
    }

    @Test
    public void testPreventRootDomainAdminAccessToRootAdminKeysNormalUser() {
        User user = Mockito.mock(User.class);
        ControlledEntity entity = Mockito.mock(ControlledEntity.class);
        Mockito.when(user.getAccountId()).thenReturn(1L);
        AccountVO account = Mockito.mock(AccountVO.class);
        Mockito.when(account.getType()).thenReturn(Account.Type.NORMAL);
        Mockito.when(_accountDao.findById(1L)).thenReturn(account);
        accountManagerImpl.preventRootDomainAdminAccessToRootAdminKeys(user, entity);
        Mockito.verify(accountManagerImpl, Mockito.never()).isRootAdmin(Mockito.anyLong());
    }

    @Test(expected = PermissionDeniedException.class)
    public void testPreventRootDomainAdminAccessToRootAdminKeysRootDomainAdminUser() {
        User user = Mockito.mock(User.class);
        ControlledEntity entity = Mockito.mock(ControlledEntity.class);
        Mockito.when(user.getAccountId()).thenReturn(1L);
        AccountVO account = Mockito.mock(AccountVO.class);
        Mockito.when(account.getType()).thenReturn(Account.Type.DOMAIN_ADMIN);
        Mockito.when(account.getDomainId()).thenReturn(Domain.ROOT_DOMAIN);
        Mockito.when(_accountDao.findById(1L)).thenReturn(account);
        Mockito.when(entity.getAccountId()).thenReturn(1L);
        Mockito.lenient().when(securityChecker.checkAccess(Mockito.any(Account.class),
                Mockito.nullable(ControlledEntity.class), Mockito.nullable(AccessType.class), Mockito.anyString())).thenReturn(true);
        accountManagerImpl.preventRootDomainAdminAccessToRootAdminKeys(user, entity);
    }

    @Test
    public void updateUserTestTimeZoneAndEmailNull() {
        Mockito.when(userVoMock.getAccountId()).thenReturn(10L);
        Mockito.doReturn(accountMock).when(accountManagerImpl).getAccount(10L);
        Mockito.when(accountMock.getAccountId()).thenReturn(10L);
        Mockito.doReturn(false).when(accountManagerImpl).isRootAdmin(10L);
        Mockito.lenient().when(accountManagerImpl.getRoleType(Mockito.eq(accountMock))).thenReturn(RoleType.User);

        prepareMockAndExecuteUpdateUserTest(0);
    }

    @Test
    public void updateUserTestTimeZoneAndEmailNotNull() {
        Mockito.when(UpdateUserCmdMock.getEmail()).thenReturn("email");
        Mockito.when(UpdateUserCmdMock.getTimezone()).thenReturn("timezone");
        Mockito.when(userVoMock.getAccountId()).thenReturn(10L);
        Mockito.doReturn(accountMock).when(accountManagerImpl).getAccount(10L);
        Mockito.when(accountMock.getAccountId()).thenReturn(10L);
        Mockito.doReturn(false).when(accountManagerImpl).isRootAdmin(10L);
        Mockito.lenient().when(accountManagerImpl.getRoleType(Mockito.eq(accountMock))).thenReturn(RoleType.User);
        prepareMockAndExecuteUpdateUserTest(1);
    }

    private void prepareMockAndExecuteUpdateUserTest(int numberOfExpectedCallsForSetEmailAndSetTimeZone) {
        Mockito.doReturn("password").when(UpdateUserCmdMock).getPassword();
        Mockito.doReturn("newpassword").when(UpdateUserCmdMock).getCurrentPassword();
        Mockito.doReturn(userVoMock).when(accountManagerImpl).retrieveAndValidateUser(UpdateUserCmdMock);
        Mockito.doNothing().when(accountManagerImpl).validateAndUpdateApiAndSecretKeyIfNeeded(UpdateUserCmdMock, userVoMock);
        Mockito.doReturn(accountMock).when(accountManagerImpl).retrieveAndValidateAccount(userVoMock);

        Mockito.doNothing().when(accountManagerImpl).validateAndUpdateFirstNameIfNeeded(UpdateUserCmdMock, userVoMock);
        Mockito.doNothing().when(accountManagerImpl).validateAndUpdateLastNameIfNeeded(UpdateUserCmdMock, userVoMock);
        Mockito.doNothing().when(accountManagerImpl).validateAndUpdateUsernameIfNeeded(UpdateUserCmdMock, userVoMock, accountMock);
        Mockito.doNothing().when(accountManagerImpl).validateUserPasswordAndUpdateIfNeeded(Mockito.anyString(), Mockito.eq(userVoMock), Mockito.anyString(), Mockito.eq(false));

        Mockito.doReturn(true).when(userDaoMock).update(Mockito.anyLong(), Mockito.eq(userVoMock));
        Mockito.doReturn(Mockito.mock(UserAccountVO.class)).when(userAccountDaoMock).findById(Mockito.anyLong());
        Mockito.doNothing().when(accountManagerImpl).checkAccess(nullable(User.class), nullable(Account.class));

        accountManagerImpl.updateUser(UpdateUserCmdMock);

        Mockito.lenient().doNothing().when(accountManagerImpl).checkRoleEscalation(accountMock, accountMock);

        InOrder inOrder = Mockito.inOrder(userVoMock, accountManagerImpl, userDaoMock, userAccountDaoMock);

        inOrder.verify(accountManagerImpl).retrieveAndValidateUser(UpdateUserCmdMock);
        inOrder.verify(accountManagerImpl).retrieveAndValidateAccount(userVoMock);
        inOrder.verify(accountManagerImpl).validateAndUpdateApiAndSecretKeyIfNeeded(UpdateUserCmdMock, userVoMock);

        inOrder.verify(accountManagerImpl).validateAndUpdateFirstNameIfNeeded(UpdateUserCmdMock, userVoMock);
        inOrder.verify(accountManagerImpl).validateAndUpdateLastNameIfNeeded(UpdateUserCmdMock, userVoMock);
        inOrder.verify(accountManagerImpl).validateAndUpdateUsernameIfNeeded(UpdateUserCmdMock, userVoMock, accountMock);
        inOrder.verify(accountManagerImpl).validateUserPasswordAndUpdateIfNeeded(UpdateUserCmdMock.getPassword(), userVoMock, UpdateUserCmdMock.getCurrentPassword(), false);

        inOrder.verify(userVoMock, Mockito.times(numberOfExpectedCallsForSetEmailAndSetTimeZone)).setEmail(Mockito.anyString());
        inOrder.verify(userVoMock, Mockito.times(numberOfExpectedCallsForSetEmailAndSetTimeZone)).setTimezone(Mockito.anyString());

        inOrder.verify(userDaoMock).update(Mockito.anyLong(), Mockito.eq(userVoMock));
        inOrder.verify(userAccountDaoMock).findById(Mockito.anyLong());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void retrieveAndValidateUserTestNoUserFound() {
        Mockito.doReturn(null).when(userDaoMock).getUser(Mockito.anyLong());

        accountManagerImpl.retrieveAndValidateUser(UpdateUserCmdMock);
    }

    @Test
    public void retrieveAndValidateUserTestUserIsFound() {
        Mockito.doReturn(userVoMock).when(userDaoMock).getUser(Mockito.anyLong());

        UserVO receivedUser = accountManagerImpl.retrieveAndValidateUser(UpdateUserCmdMock);

        Assert.assertEquals(userVoMock, receivedUser);
    }

    @Test
    public void validateAndUpdatApiAndSecretKeyIfNeededTestNoKeys() {
        accountManagerImpl.validateAndUpdateApiAndSecretKeyIfNeeded(UpdateUserCmdMock, userVoMock);

        Mockito.verify(_accountDao, Mockito.times(0)).findUserAccountByApiKey(Mockito.anyString());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateAndUpdatApiAndSecretKeyIfNeededTestOnlyApiKeyInformed() {
        Mockito.doReturn("apiKey").when(UpdateUserCmdMock).getApiKey();

        accountManagerImpl.validateAndUpdateApiAndSecretKeyIfNeeded(UpdateUserCmdMock, userVoMock);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateAndUpdatApiAndSecretKeyIfNeededTestOnlySecretKeyInformed() {
        Mockito.doReturn("secretKey").when(UpdateUserCmdMock).getSecretKey();

        accountManagerImpl.validateAndUpdateApiAndSecretKeyIfNeeded(UpdateUserCmdMock, userVoMock);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateAndUpdatApiAndSecretKeyIfNeededTestApiKeyAlreadyUsedBySomeoneElse() {
        String apiKey = "apiKey";
        Mockito.doReturn(apiKey).when(UpdateUserCmdMock).getApiKey();
        Mockito.doReturn("secretKey").when(UpdateUserCmdMock).getSecretKey();

        Mockito.doReturn(1L).when(userVoMock).getId();

        User otherUserMock = Mockito.mock(User.class);
        Mockito.doReturn(2L).when(otherUserMock).getId();

        Pair<User, Account> pairUserAccountMock = new Pair<User, Account>(otherUserMock, Mockito.mock(Account.class));
        Mockito.doReturn(pairUserAccountMock).when(_accountDao).findUserAccountByApiKey(apiKey);

        accountManagerImpl.validateAndUpdateApiAndSecretKeyIfNeeded(UpdateUserCmdMock, userVoMock);
    }

    @Test
    public void validateAndUpdatApiAndSecretKeyIfNeededTest() {
        String apiKey = "apiKey";
        Mockito.doReturn(apiKey).when(UpdateUserCmdMock).getApiKey();

        String secretKey = "secretKey";
        Mockito.doReturn(secretKey).when(UpdateUserCmdMock).getSecretKey();

        Mockito.doReturn(1L).when(userVoMock).getId();

        User otherUserMock = Mockito.mock(User.class);
        Mockito.doReturn(1L).when(otherUserMock).getId();

        Pair<User, Account> pairUserAccountMock = new Pair<User, Account>(otherUserMock, Mockito.mock(Account.class));
        Mockito.doReturn(pairUserAccountMock).when(_accountDao).findUserAccountByApiKey(apiKey);

        accountManagerImpl.validateAndUpdateApiAndSecretKeyIfNeeded(UpdateUserCmdMock, userVoMock);

        Mockito.verify(_accountDao).findUserAccountByApiKey(apiKey);
        Mockito.verify(userVoMock).setApiKey(apiKey);
        Mockito.verify(userVoMock).setSecretKey(secretKey);
    }

    @Test
    public void validateAndUpdatUserApiKeyAccess() {
        Mockito.doReturn("Enabled").when(UpdateUserCmdMock).getApiKeyAccess();
        try (MockedStatic<ActionEventUtils> eventUtils = Mockito.mockStatic(ActionEventUtils.class)) {
            Mockito.when(ActionEventUtils.onActionEvent(Mockito.anyLong(), Mockito.anyLong(),
                    Mockito.anyLong(),
                    Mockito.anyString(), Mockito.anyString(),
                    Mockito.anyLong(), Mockito.anyString())).thenReturn(1L);
            accountManagerImpl.validateAndUpdateUserApiKeyAccess(UpdateUserCmdMock, userVoMock);
        }

        Mockito.verify(userVoMock).setApiKeyAccess(true);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateAndUpdatUserApiKeyAccessInvalidParameter() {
        Mockito.doReturn("False").when(UpdateUserCmdMock).getApiKeyAccess();
        accountManagerImpl.validateAndUpdateUserApiKeyAccess(UpdateUserCmdMock, userVoMock);
    }

    @Test
    public void validateAndUpdatAccountApiKeyAccess() {
        Mockito.doReturn("Inherit").when(UpdateAccountCmdMock).getApiKeyAccess();
        try (MockedStatic<ActionEventUtils> eventUtils = Mockito.mockStatic(ActionEventUtils.class)) {
            Mockito.when(ActionEventUtils.onActionEvent(Mockito.anyLong(), Mockito.anyLong(),
                    Mockito.anyLong(),
                    Mockito.anyString(), Mockito.anyString(),
                    Mockito.anyLong(), Mockito.anyString())).thenReturn(1L);
            accountManagerImpl.validateAndUpdateAccountApiKeyAccess(UpdateAccountCmdMock, accountVoMock);
        }

        Mockito.verify(accountVoMock).setApiKeyAccess(null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateAndUpdatAccountApiKeyAccessInvalidParameter() {
        Mockito.doReturn("False").when(UpdateAccountCmdMock).getApiKeyAccess();
        accountManagerImpl.validateAndUpdateAccountApiKeyAccess(UpdateAccountCmdMock, accountVoMock);
    }

    @Test(expected = CloudRuntimeException.class)
    public void retrieveAndValidateAccountTestAccountNotFound() {
        Mockito.doReturn(accountMockId).when(userVoMock).getAccountId();

        Mockito.doReturn(null).when(_accountDao).findById(accountMockId);

        accountManagerImpl.retrieveAndValidateAccount(userVoMock);
    }

    @Test
    public void retrieveAndValidateAccountTestAccountTypeEqualsProjectType() {
        Mockito.doReturn(accountMockId).when(userVoMock).getAccountId();
        Mockito.lenient().doReturn(Account.Type.PROJECT).when(accountMock).getType();
        Mockito.doReturn(callingAccount).when(_accountDao).findById(accountMockId);
        Mockito.doNothing().when(accountManagerImpl).checkAccess(Mockito.any(Account.class), Mockito.any(AccessType.class), Mockito.anyBoolean(), Mockito.any(Account.class));

        accountManagerImpl.retrieveAndValidateAccount(userVoMock);
    }

    @Test
    public void retrieveAndValidateAccountTestAccountTypeEqualsSystemType() {
        Mockito.doReturn(Account.ACCOUNT_ID_SYSTEM).when(userVoMock).getAccountId();
        Mockito.doReturn(Account.ACCOUNT_ID_SYSTEM).when(accountMock).getId();
        Mockito.doReturn(callingAccount).when(_accountDao).findById(Account.ACCOUNT_ID_SYSTEM);
        accountManagerImpl.retrieveAndValidateAccount(userVoMock);
    }

    @Test
    public void retrieveAndValidateAccountTest() {
        Mockito.doReturn(accountMockId).when(userVoMock).getAccountId();
        Mockito.doReturn(callingAccount).when(_accountDao).findById(accountMockId);

        Mockito.doNothing().when(accountManagerImpl).checkAccess(Mockito.eq(accountMock), Mockito.eq(AccessType.OperateEntry), Mockito.anyBoolean(), Mockito.any(Account.class));
        accountManagerImpl.retrieveAndValidateAccount(userVoMock);

        Mockito.verify(accountManagerImpl).getCurrentCallingAccount();
        Mockito.verify(accountManagerImpl).checkAccess(Mockito.eq(accountMock), Mockito.eq(AccessType.OperateEntry), Mockito.anyBoolean(), Mockito.any(Account.class));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateAndUpdateFirstNameIfNeededTestFirstNameBlank() {
        Mockito.doReturn("   ").when(UpdateUserCmdMock).getFirstname();

        accountManagerImpl.validateAndUpdateFirstNameIfNeeded(UpdateUserCmdMock, userVoMock);
    }

    @Test
    public void validateAndUpdateFirstNameIfNeededTestFirstNameNull() {
        Mockito.doReturn(null).when(UpdateUserCmdMock).getFirstname();

        accountManagerImpl.validateAndUpdateFirstNameIfNeeded(UpdateUserCmdMock, userVoMock);

        Mockito.verify(userVoMock, Mockito.times(0)).setFirstname(Mockito.anyString());
    }

    @Test
    public void validateAndUpdateFirstNameIfNeededTest() {
        String firstname = "firstName";
        Mockito.doReturn(firstname).when(UpdateUserCmdMock).getFirstname();

        accountManagerImpl.validateAndUpdateFirstNameIfNeeded(UpdateUserCmdMock, userVoMock);

        Mockito.verify(userVoMock).setFirstname(firstname);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateAndUpdateLastNameIfNeededTestLastNameBlank() {
        Mockito.doReturn("   ").when(UpdateUserCmdMock).getLastname();

        accountManagerImpl.validateAndUpdateLastNameIfNeeded(UpdateUserCmdMock, userVoMock);
    }

    @Test
    public void validateAndUpdateLastNameIfNeededTestLastNameNull() {
        Mockito.doReturn(null).when(UpdateUserCmdMock).getLastname();

        accountManagerImpl.validateAndUpdateLastNameIfNeeded(UpdateUserCmdMock, userVoMock);

        Mockito.verify(userVoMock, Mockito.times(0)).setLastname(Mockito.anyString());
    }

    @Test
    public void validateAndUpdateLastNameIfNeededTest() {
        String lastName = "lastName";
        Mockito.doReturn(lastName).when(UpdateUserCmdMock).getLastname();

        accountManagerImpl.validateAndUpdateLastNameIfNeeded(UpdateUserCmdMock, userVoMock);

        Mockito.verify(userVoMock).setLastname(lastName);
    }

    @Test
    public void validateAndUpdateUsernameIfNeededTestNullUsername() {
        Mockito.doReturn(null).when(UpdateUserCmdMock).getUsername();

        accountManagerImpl.validateAndUpdateUsernameIfNeeded(UpdateUserCmdMock, userVoMock, accountMock);

        Mockito.verify(userVoMock, Mockito.times(0)).setUsername(Mockito.anyString());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateAndUpdateUsernameIfNeededTestBlankUsername() {
        Mockito.doReturn("   ").when(UpdateUserCmdMock).getUsername();

        accountManagerImpl.validateAndUpdateUsernameIfNeeded(UpdateUserCmdMock, userVoMock, accountMock);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateAndUpdateUsernameIfNeededTestDuplicatedUserSameDomainThisUser() {
        long domanIdCurrentUser = 22l;

        String userName = "username";
        Mockito.doReturn(userName).when(UpdateUserCmdMock).getUsername();
        Mockito.lenient().doReturn(userName).when(userVoMock).getUsername();
        Mockito.doReturn(domanIdCurrentUser).when(accountMock).getDomainId();

        long userVoDuplicatedMockId = 67l;
        UserVO userVoDuplicatedMock = Mockito.mock(UserVO.class);
        Mockito.doReturn(userVoDuplicatedMockId).when(userVoDuplicatedMock).getId();

        long accountIdUserDuplicated = 98l;

        Mockito.doReturn(accountIdUserDuplicated).when(userVoDuplicatedMock).getAccountId();

        Account accountUserDuplicatedMock = Mockito.mock(AccountVO.class);
        Mockito.lenient().doReturn(accountIdUserDuplicated).when(accountUserDuplicatedMock).getId();
        Mockito.doReturn(domanIdCurrentUser).when(accountUserDuplicatedMock).getDomainId();

        List<UserVO> usersWithSameUserName = new ArrayList<>();
        usersWithSameUserName.add(userVoMock);
        usersWithSameUserName.add(userVoDuplicatedMock);

        Mockito.doReturn(usersWithSameUserName).when(userDaoMock).findUsersByName(userName);

        Mockito.lenient().doReturn(accountMock).when(_accountDao).findById(accountMockId);
        Mockito.doReturn(accountUserDuplicatedMock).when(_accountDao).findById(accountIdUserDuplicated);

        Mockito.doReturn(Mockito.mock(DomainVO.class)).when(_domainDao).findById(Mockito.anyLong());

        accountManagerImpl.validateAndUpdateUsernameIfNeeded(UpdateUserCmdMock, userVoMock, accountMock);
    }

    @Test
    public void validateAndUpdateUsernameIfNeededTestDuplicatedUserButInDifferentDomains() {
        long domanIdCurrentUser = 22l;

        String userName = "username";
        Mockito.doReturn(userName).when(UpdateUserCmdMock).getUsername();
        Mockito.lenient().doReturn(userName).when(userVoMock).getUsername();
        Mockito.doReturn(domanIdCurrentUser).when(accountMock).getDomainId();

        long userVoDuplicatedMockId = 67l;
        UserVO userVoDuplicatedMock = Mockito.mock(UserVO.class);
        Mockito.lenient().doReturn(userName).when(userVoDuplicatedMock).getUsername();
        Mockito.doReturn(userVoDuplicatedMockId).when(userVoDuplicatedMock).getId();

        long accountIdUserDuplicated = 98l;
        Mockito.doReturn(accountIdUserDuplicated).when(userVoDuplicatedMock).getAccountId();

        Account accountUserDuplicatedMock = Mockito.mock(AccountVO.class);
        Mockito.lenient().doReturn(accountIdUserDuplicated).when(accountUserDuplicatedMock).getId();
        Mockito.doReturn(45l).when(accountUserDuplicatedMock).getDomainId();

        List<UserVO> usersWithSameUserName = new ArrayList<>();
        usersWithSameUserName.add(userVoMock);
        usersWithSameUserName.add(userVoDuplicatedMock);

        Mockito.doReturn(usersWithSameUserName).when(userDaoMock).findUsersByName(userName);

        Mockito.lenient().doReturn(accountMock).when(_accountDao).findById(accountMockId);
        Mockito.doReturn(accountUserDuplicatedMock).when(_accountDao).findById(accountIdUserDuplicated);

        accountManagerImpl.validateAndUpdateUsernameIfNeeded(UpdateUserCmdMock, userVoMock, accountMock);

        Mockito.verify(userVoMock).setUsername(userName);
    }

    @Test
    public void validateAndUpdateUsernameIfNeededTestNoDuplicatedUserNames() {
        long domanIdCurrentUser = 22l;

        String userName = "username";
        Mockito.doReturn(userName).when(UpdateUserCmdMock).getUsername();
        Mockito.lenient().doReturn(userName).when(userVoMock).getUsername();
        Mockito.lenient().doReturn(domanIdCurrentUser).when(accountMock).getDomainId();

        List<UserVO> usersWithSameUserName = new ArrayList<>();

        Mockito.doReturn(usersWithSameUserName).when(userDaoMock).findUsersByName(userName);

        Mockito.lenient().doReturn(accountMock).when(_accountDao).findById(accountMockId);

        accountManagerImpl.validateAndUpdateUsernameIfNeeded(UpdateUserCmdMock, userVoMock, accountMock);

        Mockito.verify(userVoMock).setUsername(userName);
    }

    @Test
    public void valiateUserPasswordAndUpdateIfNeededTestPasswordNull() {
        accountManagerImpl.validateUserPasswordAndUpdateIfNeeded(null, userVoMock, null, false);

        Mockito.verify(userVoMock, Mockito.times(0)).setPassword(Mockito.anyString());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void valiateUserPasswordAndUpdateIfNeededTestBlankPassword() {
        accountManagerImpl.validateUserPasswordAndUpdateIfNeeded("       ", userVoMock, null, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void valiateUserPasswordAndUpdateIfNeededTestNoAdminAndNoCurrentPasswordProvided() {
        Mockito.doReturn(accountMock).when(accountManagerImpl).getCurrentCallingAccount();
        Mockito.doReturn(false).when(accountManagerImpl).isRootAdmin(accountMockId);
        Mockito.doReturn(false).when(accountManagerImpl).isDomainAdmin(accountMockId);
        Mockito.lenient().doReturn(true).when(accountManagerImpl).isResourceDomainAdmin(accountMockId);

        Mockito.doReturn(accountMock).when(accountManagerImpl).getAccount(Mockito.anyLong());

        Mockito.lenient().doNothing().when(passwordPolicyMock).verifyIfPasswordCompliesWithPasswordPolicies(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong());

        accountManagerImpl.validateUserPasswordAndUpdateIfNeeded("newPassword", userVoMock, "  ", false);
    }

    @Test(expected = CloudRuntimeException.class)
    public void valiateUserPasswordAndUpdateIfNeededTestNoUserAuthenticatorsConfigured() {
        Mockito.doReturn(accountMock).when(accountManagerImpl).getCurrentCallingAccount();
        Mockito.doReturn(true).when(accountManagerImpl).isRootAdmin(accountMockId);
        Mockito.doReturn(false).when(accountManagerImpl).isDomainAdmin(accountMockId);

        Mockito.lenient().doNothing().when(accountManagerImpl).validateCurrentPassword(Mockito.eq(userVoMock), Mockito.anyString());

        Mockito.doReturn(accountMock).when(accountManagerImpl).getAccount(Mockito.anyLong());

        Mockito.lenient().doNothing().when(passwordPolicyMock).verifyIfPasswordCompliesWithPasswordPolicies(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong());

        accountManagerImpl.validateUserPasswordAndUpdateIfNeeded("newPassword", userVoMock, null, false);
    }

    @Test
    public void validateUserPasswordAndUpdateIfNeededTestRootAdminUpdatingUserPassword() {
        Mockito.doReturn(accountMock).when(accountManagerImpl).getCurrentCallingAccount();
        Mockito.doReturn(true).when(accountManagerImpl).isRootAdmin(accountMockId);
        Mockito.doReturn(false).when(accountManagerImpl).isDomainAdmin(accountMockId);

        String newPassword = "newPassword";

        String expectedUserPasswordAfterEncoded = configureUserMockAuthenticators(newPassword);

        Mockito.lenient().doNothing().when(accountManagerImpl).validateCurrentPassword(Mockito.eq(userVoMock), Mockito.anyString());

        Mockito.doReturn(accountMock).when(accountManagerImpl).getAccount(Mockito.anyLong());

        Mockito.lenient().doNothing().when(passwordPolicyMock).verifyIfPasswordCompliesWithPasswordPolicies(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong());

        accountManagerImpl.validateUserPasswordAndUpdateIfNeeded(newPassword, userVoMock, null, false);

        Mockito.verify(accountManagerImpl, Mockito.times(0)).validateCurrentPassword(Mockito.eq(userVoMock), Mockito.anyString());
        Mockito.verify(userVoMock, Mockito.times(1)).setPassword(expectedUserPasswordAfterEncoded);
    }

    @Test
    public void validateUserPasswordAndUpdateIfNeededTestDomainAdminUpdatingUserPassword() {
        Mockito.doReturn(accountMock).when(accountManagerImpl).getCurrentCallingAccount();
        Mockito.doReturn(false).when(accountManagerImpl).isRootAdmin(accountMockId);
        Mockito.doReturn(true).when(accountManagerImpl).isDomainAdmin(accountMockId);

        String newPassword = "newPassword";

        String expectedUserPasswordAfterEncoded = configureUserMockAuthenticators(newPassword);

        Mockito.lenient().doNothing().when(accountManagerImpl).validateCurrentPassword(Mockito.eq(userVoMock), Mockito.anyString());

        Mockito.doReturn(accountMock).when(accountManagerImpl).getAccount(Mockito.anyLong());

        Mockito.lenient().doNothing().when(passwordPolicyMock).verifyIfPasswordCompliesWithPasswordPolicies(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong());

        accountManagerImpl.validateUserPasswordAndUpdateIfNeeded(newPassword, userVoMock, null, false);

        Mockito.verify(accountManagerImpl, Mockito.times(0)).validateCurrentPassword(Mockito.eq(userVoMock), Mockito.anyString());
        Mockito.verify(userVoMock, Mockito.times(1)).setPassword(expectedUserPasswordAfterEncoded);
    }

    @Test
    public void validateUserPasswordAndUpdateIfNeededTestUserUpdatingHisPassword() {
        Mockito.doReturn(accountMock).when(accountManagerImpl).getCurrentCallingAccount();
        Mockito.doReturn(false).when(accountManagerImpl).isRootAdmin(accountMockId);
        Mockito.doReturn(false).when(accountManagerImpl).isDomainAdmin(accountMockId);

        String newPassword = "newPassword";
        String expectedUserPasswordAfterEncoded = configureUserMockAuthenticators(newPassword);

        Mockito.doNothing().when(accountManagerImpl).validateCurrentPassword(Mockito.eq(userVoMock), Mockito.anyString());

        String currentPassword = "theCurrentPassword";

        Mockito.doReturn(accountMock).when(accountManagerImpl).getAccount(Mockito.anyLong());

        Mockito.lenient().doNothing().when(passwordPolicyMock).verifyIfPasswordCompliesWithPasswordPolicies(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong());

        accountManagerImpl.validateUserPasswordAndUpdateIfNeeded(newPassword, userVoMock, currentPassword, false);

        Mockito.verify(accountManagerImpl, Mockito.times(1)).validateCurrentPassword(userVoMock, currentPassword);
        Mockito.verify(userVoMock, Mockito.times(1)).setPassword(expectedUserPasswordAfterEncoded);
    }

    @Test (expected = InvalidParameterValueException.class)
    public void validateUserPasswordAndUpdateIfNeededTestIfVerifyIfPasswordCompliesWithPasswordPoliciesThrowsException() {
        String newPassword = "newPassword";

        String currentPassword = "theCurrentPassword";

        Mockito.doReturn(accountMock).when(accountManagerImpl).getAccount(Mockito.anyLong());

        Mockito.doReturn("user").when(userVoMock).getUsername();

        Mockito.doThrow(new InvalidParameterValueException("")).when(passwordPolicyMock).verifyIfPasswordCompliesWithPasswordPolicies(Mockito.anyString(), Mockito.anyString(),
                Mockito.anyLong());

        accountManagerImpl.validateUserPasswordAndUpdateIfNeeded(newPassword, userVoMock, currentPassword, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateUserPasswordAndUpdateIfNeededTestSaml2UserShouldNotBeAllowedToUpdateTheirPassword() {
        String newPassword = "newPassword";
        String currentPassword = "theCurrentPassword";

        Mockito.when(userVoMock.getSource()).thenReturn(User.Source.SAML2);

        accountManagerImpl.validateUserPasswordAndUpdateIfNeeded(newPassword, userVoMock, currentPassword, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateUserPasswordAndUpdateIfNeededTestSaml2DisabledUserShouldNotBeAllowedToUpdateTheirPassword() {
        String newPassword = "newPassword";
        String currentPassword = "theCurrentPassword";

        Mockito.when(userVoMock.getSource()).thenReturn(User.Source.SAML2DISABLED);

        accountManagerImpl.validateUserPasswordAndUpdateIfNeeded(newPassword, userVoMock, currentPassword, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateUserPasswordAndUpdateIfNeededTestLdapUserShouldNotBeAllowedToUpdateTheirPassword() {
        String newPassword = "newPassword";
        String currentPassword = "theCurrentPassword";

        Mockito.when(userVoMock.getSource()).thenReturn(User.Source.LDAP);

        accountManagerImpl.validateUserPasswordAndUpdateIfNeeded(newPassword, userVoMock, currentPassword, false);
    }

    private String configureUserMockAuthenticators(String newPassword) {
        accountManagerImpl._userPasswordEncoders = new ArrayList<>();
        UserAuthenticator authenticatorMock1 = Mockito.mock(UserAuthenticator.class);
        String expectedUserPasswordAfterEncoded = "passwordEncodedByAuthenticator1";
        Mockito.doReturn(expectedUserPasswordAfterEncoded).when(authenticatorMock1).encode(newPassword);

        UserAuthenticator authenticatorMock2 = Mockito.mock(UserAuthenticator.class);
        Mockito.lenient().doReturn("passwordEncodedByAuthenticator2").when(authenticatorMock2).encode(newPassword);

        accountManagerImpl._userPasswordEncoders.add(authenticatorMock1);
        accountManagerImpl._userPasswordEncoders.add(authenticatorMock2);
        return expectedUserPasswordAfterEncoded;
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateCurrentPasswordTestUserNotAuthenticatedWithProvidedCurrentPassword() {
        Mockito.doReturn(Mockito.mock(AccountVO.class)).when(_accountDao).findById(accountMockId);
        String newPassword = "newPassword";
        configureUserMockAuthenticators(newPassword);

        accountManagerImpl.validateCurrentPassword(userVoMock, "currentPassword");
    }

    @Test
    public void validateCurrentPasswordTestUserAuthenticatedWithProvidedCurrentPasswordViaFirstAuthenticator() {
        AccountVO accountVoMock = Mockito.mock(AccountVO.class);
        long domainId = 14l;
        Mockito.doReturn(domainId).when(accountVoMock).getDomainId();

        Mockito.doReturn(accountVoMock).when(_accountDao).findById(accountMockId);
        String username = "username";
        Mockito.doReturn(username).when(userVoMock).getUsername();

        accountManagerImpl._userPasswordEncoders = new ArrayList<>();
        UserAuthenticator authenticatorMock1 = Mockito.mock(UserAuthenticator.class);
        UserAuthenticator authenticatorMock2 = Mockito.mock(UserAuthenticator.class);

        accountManagerImpl._userPasswordEncoders.add(authenticatorMock1);
        accountManagerImpl._userPasswordEncoders.add(authenticatorMock2);

        Pair<Boolean, ActionOnFailedAuthentication> authenticationResult = new Pair<Boolean, UserAuthenticator.ActionOnFailedAuthentication>(true,
                UserAuthenticator.ActionOnFailedAuthentication.INCREMENT_INCORRECT_LOGIN_ATTEMPT_COUNT);

        String currentPassword = "currentPassword";
        Mockito.doReturn(authenticationResult).when(authenticatorMock1).authenticate(username, currentPassword, domainId, null);

        accountManagerImpl.validateCurrentPassword(userVoMock, currentPassword);

        Mockito.verify(authenticatorMock1, Mockito.times(1)).authenticate(username, currentPassword, domainId, null);
        Mockito.verify(authenticatorMock2, Mockito.times(0)).authenticate(username, currentPassword, domainId, null);
    }

    @Test
    public void validateCurrentPasswordTestUserAuthenticatedWithProvidedCurrentPasswordViaSecondAuthenticator() {
        AccountVO accountVoMock = Mockito.mock(AccountVO.class);
        long domainId = 14l;
        Mockito.doReturn(domainId).when(accountVoMock).getDomainId();

        Mockito.doReturn(accountVoMock).when(_accountDao).findById(accountMockId);
        String username = "username";
        Mockito.doReturn(username).when(userVoMock).getUsername();

        accountManagerImpl._userPasswordEncoders = new ArrayList<>();
        UserAuthenticator authenticatorMock1 = Mockito.mock(UserAuthenticator.class);
        UserAuthenticator authenticatorMock2 = Mockito.mock(UserAuthenticator.class);

        accountManagerImpl._userPasswordEncoders.add(authenticatorMock1);
        accountManagerImpl._userPasswordEncoders.add(authenticatorMock2);

        Pair<Boolean, ActionOnFailedAuthentication> authenticationResult = new Pair<Boolean, UserAuthenticator.ActionOnFailedAuthentication>(true,
                UserAuthenticator.ActionOnFailedAuthentication.INCREMENT_INCORRECT_LOGIN_ATTEMPT_COUNT);

        String currentPassword = "currentPassword";
        Mockito.doReturn(authenticationResult).when(authenticatorMock2).authenticate(username, currentPassword, domainId, null);

        accountManagerImpl.validateCurrentPassword(userVoMock, currentPassword);

        Mockito.verify(authenticatorMock1, Mockito.times(1)).authenticate(username, currentPassword, domainId, null);
        Mockito.verify(authenticatorMock2, Mockito.times(1)).authenticate(username, currentPassword, domainId, null);
    }

    @Test
    public void testUpdateLoginAttemptsDisableMechanism() {
        accountManagerImpl.updateLoginAttemptsWhenIncorrectLoginAttemptsEnabled(userAccountVO, true, 0);
        Mockito.verify(accountManagerImpl, Mockito.never()).updateLoginAttempts(Mockito.anyLong(), Mockito.anyInt(), Mockito.anyBoolean());
    }

    @Test
    public void testUpdateLoginAttemptsEnableMechanismAttemptsLeft() {
        int attempts = 2;
        int allowedAttempts = 5;
        Long accountId = 1L;
        Mockito.when(userAccountVO.getLoginAttempts()).thenReturn(attempts);
        Mockito.when(userAccountVO.getId()).thenReturn(accountId);
        accountManagerImpl.updateLoginAttemptsWhenIncorrectLoginAttemptsEnabled(userAccountVO, true, allowedAttempts);
        Mockito.verify(accountManagerImpl).updateLoginAttempts(Mockito.eq(accountId), Mockito.eq(attempts + 1), Mockito.eq(false));
    }

    @Test
    public void testUpdateLoginAttemptsEnableMechanismNoAttemptsLeft() {
        int attempts = 5;
        int allowedAttempts = 5;
        Long accountId = 1L;
        Mockito.when(userAccountVO.getLoginAttempts()).thenReturn(attempts);
        Mockito.when(userAccountVO.getId()).thenReturn(accountId);
        accountManagerImpl.updateLoginAttemptsWhenIncorrectLoginAttemptsEnabled(userAccountVO, true, allowedAttempts);
        Mockito.verify(accountManagerImpl).updateLoginAttempts(Mockito.eq(accountId), Mockito.eq(allowedAttempts), Mockito.eq(true));
    }

    @Test(expected = CloudRuntimeException.class)
    public void testEnableUserTwoFactorAuthenticationWhenDomainlevelSettingisDisabled() {
        Long userId = 1L;

        UserAccountVO userAccount = Mockito.mock(UserAccountVO.class);
        UserVO userVO = Mockito.mock(UserVO.class);

        Mockito.when(userAccountDaoMock.findById(userId)).thenReturn(userAccount);
        Mockito.when(userDaoMock.findById(userId)).thenReturn(userVO);
        Mockito.when(userAccount.getDomainId()).thenReturn(1L);

        ConfigKey<Boolean> enableUserTwoFactorAuthentication = Mockito.mock(ConfigKey.class);
        AccountManagerImpl.enableUserTwoFactorAuthentication = enableUserTwoFactorAuthentication;

        Mockito.when(enableUserTwoFactorAuthentication.valueIn(1L)).thenReturn(false);

        accountManagerImpl.enableTwoFactorAuthentication(userId, "totp");
    }

    @Test
    public void testEnableUserTwoFactorAuthenticationWhenProviderNameIsNullExpectedDefaultProviderTOTP() {
        Long userId = 1L;

        UserAccountVO userAccount = Mockito.mock(UserAccountVO.class);
        UserVO userVO = Mockito.mock(UserVO.class);

        Mockito.when(userAccountDaoMock.findById(userId)).thenReturn(userAccount);
        Mockito.when(userDaoMock.findById(userId)).thenReturn(userVO);
        Mockito.when(userAccount.getDomainId()).thenReturn(1L);

        ConfigKey<Boolean> enableUserTwoFactorAuthentication = Mockito.mock(ConfigKey.class);
        AccountManagerImpl.enableUserTwoFactorAuthentication = enableUserTwoFactorAuthentication;
        Mockito.when(enableUserTwoFactorAuthentication.valueIn(1L)).thenReturn(true);

        UserTwoFactorAuthenticator totpProvider = Mockito.mock(UserTwoFactorAuthenticator.class);
        Map<String, UserTwoFactorAuthenticator> userTwoFactorAuthenticationProvidersMap = Mockito.mock(HashMap.class);
        Mockito.when(userTwoFactorAuthenticationProvidersMap.containsKey("totp")).thenReturn( true);
        Mockito.when(userTwoFactorAuthenticationProvidersMap.get("totp")).thenReturn(totpProvider);
        AccountManagerImpl.userTwoFactorAuthenticationProvidersMap = userTwoFactorAuthenticationProvidersMap;
        Mockito.when(totpProvider.setup2FAKey(userAccount)).thenReturn("EUJEAEDVOURFZTE6OGWVTJZMI54QGMIL");
        Mockito.when(userDaoMock.createForUpdate()).thenReturn(userVoMock);
        Mockito.when(userDaoMock.update(userId, userVoMock)).thenReturn(true);

        UserTwoFactorAuthenticationSetupResponse response = accountManagerImpl.enableTwoFactorAuthentication(userId, null);

        Assert.assertEquals("EUJEAEDVOURFZTE6OGWVTJZMI54QGMIL", response.getSecretCode());
    }

    @Test
    public void testEnableUserTwoFactorAuthentication() {
        Long userId = 1L;

        UserAccountVO userAccount = Mockito.mock(UserAccountVO.class);
        UserVO userVO = Mockito.mock(UserVO.class);

        Mockito.when(userAccountDaoMock.findById(userId)).thenReturn(userAccount);
        Mockito.when(userDaoMock.findById(userId)).thenReturn(userVO);
        Mockito.when(userAccount.getDomainId()).thenReturn(1L);

        ConfigKey<Boolean> enableUserTwoFactorAuthentication = Mockito.mock(ConfigKey.class);
        AccountManagerImpl.enableUserTwoFactorAuthentication = enableUserTwoFactorAuthentication;
        Mockito.when(enableUserTwoFactorAuthentication.valueIn(1L)).thenReturn(true);

        UserTwoFactorAuthenticator totpProvider = Mockito.mock(UserTwoFactorAuthenticator.class);
        Map<String, UserTwoFactorAuthenticator> userTwoFactorAuthenticationProvidersMap = Mockito.mock(HashMap.class);
        Mockito.when(userTwoFactorAuthenticationProvidersMap.containsKey("totp")).thenReturn( true);
        Mockito.when(userTwoFactorAuthenticationProvidersMap.get("totp")).thenReturn(totpProvider);
        AccountManagerImpl.userTwoFactorAuthenticationProvidersMap = userTwoFactorAuthenticationProvidersMap;
        Mockito.when(totpProvider.setup2FAKey(userAccount)).thenReturn("EUJEAEDVOURFZTE6OGWVTJZMI54QGMIL");
        Mockito.when(userDaoMock.createForUpdate()).thenReturn(userVoMock);
        Mockito.when(userDaoMock.update(userId, userVoMock)).thenReturn(true);

        UserTwoFactorAuthenticationSetupResponse response = accountManagerImpl.enableTwoFactorAuthentication(userId, "totp");

        Assert.assertEquals("EUJEAEDVOURFZTE6OGWVTJZMI54QGMIL", response.getSecretCode());
    }

    @Test
    public void testDisableUserTwoFactorAuthentication() {
        Long userId = 1L;
        Long accountId = 2L;

        UserVO userVO = Mockito.mock(UserVO.class);
        Account caller = Mockito.mock(Account.class);
        Account owner = Mockito.mock(Account.class);

        Mockito.doNothing().when(accountManagerImpl).checkAccess(nullable(Account.class), Mockito.isNull(), nullable(Boolean.class), nullable(Account.class));

        Mockito.when(userDaoMock.findById(userId)).thenReturn(userVO);
        Mockito.when(userVO.getAccountId()).thenReturn(accountId);
        Mockito.when(_accountService.getActiveAccountById(accountId)).thenReturn(owner);

        userVoMock.setKeyFor2fa("EUJEAEDVOURFZTE6OGWVTJZMI54QGMIL");
        userVoMock.setUser2faProvider("totp");
        userVoMock.setUser2faEnabled(true);

        Mockito.when(userDaoMock.createForUpdate()).thenReturn(userVoMock);

        UserTwoFactorAuthenticationSetupResponse response = accountManagerImpl.disableTwoFactorAuthentication(userId, caller, owner);

        Mockito.verify(accountManagerImpl).checkAccess(caller, null, true, owner);
        Assert.assertNull(response.getSecretCode());
        Assert.assertNull(userVoMock.getKeyFor2fa());
        Assert.assertNull(userVoMock.getUser2faProvider());
    }

    @Test(expected = CloudRuntimeException.class)
    public void testVerify2FAcodeWhen2FAisNotEnabled() {
        AccountVO accountMock = Mockito.mock(AccountVO.class);
        Account caller = CallContext.current().getCallingAccount();
        Mockito.when(caller.getId()).thenReturn(1L);
        Mockito.lenient().when(_accountService.getActiveAccountById(1L)).thenReturn(accountMock);
        Mockito.when(_accountService.getUserAccountById(1L)).thenReturn(userAccountVO);
        Mockito.when(userAccountVO.isUser2faEnabled()).thenReturn(false);

        accountManagerImpl.verifyUsingTwoFactorAuthenticationCode("352352", 1L, 1L);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testVerify2FAcodeWhen2FAisNotSetup() {
        AccountVO accountMock = Mockito.mock(AccountVO.class);
        Account caller = CallContext.current().getCallingAccount();
        Mockito.when(caller.getId()).thenReturn(1L);
        Mockito.lenient().when(_accountService.getActiveAccountById(1L)).thenReturn(accountMock);
        Mockito.when(_accountService.getUserAccountById(1L)).thenReturn(userAccountVO);
        Mockito.when(userAccountVO.isUser2faEnabled()).thenReturn(true);
        Mockito.when(userAccountVO.getUser2faProvider()).thenReturn(null);

        accountManagerImpl.verifyUsingTwoFactorAuthenticationCode("352352", 1L, 1L);
    }

    @Test
    public void testVerify2FAcode() {
        AccountVO accountMock = Mockito.mock(AccountVO.class);
        Account caller = CallContext.current().getCallingAccount();
        Mockito.when(caller.getId()).thenReturn(1L);
        Mockito.lenient().when(_accountService.getActiveAccountById(1L)).thenReturn(accountMock);
        Mockito.when(_accountService.getUserAccountById(1L)).thenReturn(userAccountVO);
        Mockito.when(userAccountVO.isUser2faEnabled()).thenReturn(true);
        Mockito.when(userAccountVO.getUser2faProvider()).thenReturn("staticpin");
        Mockito.when(userAccountVO.getKeyFor2fa()).thenReturn("352352");

        UserTwoFactorAuthenticator staticpinProvider = Mockito.mock(UserTwoFactorAuthenticator.class);
        Map<String, UserTwoFactorAuthenticator> userTwoFactorAuthenticationProvidersMap = Mockito.mock(HashMap.class);
        Mockito.when(userTwoFactorAuthenticationProvidersMap.containsKey("staticpin")).thenReturn( true);
        Mockito.when(userTwoFactorAuthenticationProvidersMap.get("staticpin")).thenReturn(staticpinProvider);
        AccountManagerImpl.userTwoFactorAuthenticationProvidersMap = userTwoFactorAuthenticationProvidersMap;

        accountManagerImpl.verifyUsingTwoFactorAuthenticationCode("352352", 1L, 1L);
    }

    @Test
    public void testEnable2FAcode() {
        SetupUserTwoFactorAuthenticationCmd cmd = Mockito.mock(SetupUserTwoFactorAuthenticationCmd.class);
        Mockito.when(cmd.getProvider()).thenReturn("staticpin");

        AccountVO accountMock = Mockito.mock(AccountVO.class);
        Mockito.when(callingAccount.getId()).thenReturn(1L);
        Mockito.when(callingUser.getId()).thenReturn(1L);
        CallContext.register(callingUser, callingAccount); // Calling account is user account i.e normal account
        Mockito.lenient().when(_accountService.getActiveAccountById(1L)).thenReturn(accountMock);
        Mockito.when(userAccountDaoMock.findById(1L)).thenReturn(userAccountVO);
        Mockito.when(userDaoMock.findById(1L)).thenReturn(userVoMock);
        Mockito.when(userAccountVO.getDomainId()).thenReturn(1L);
        Mockito.when(enableUserTwoFactorAuthenticationMock.valueIn(1L)).thenReturn(true);
        Mockito.when(cmd.getEnable()).thenReturn(true);

        UserTwoFactorAuthenticator staticpinProvider = Mockito.mock(UserTwoFactorAuthenticator.class);
        Map<String, UserTwoFactorAuthenticator> userTwoFactorAuthenticationProvidersMap = Mockito.mock(HashMap.class);
        Mockito.when(userTwoFactorAuthenticationProvidersMap.containsKey("staticpin")).thenReturn( true);
        Mockito.when(userTwoFactorAuthenticationProvidersMap.get("staticpin")).thenReturn(staticpinProvider);
        Mockito.when(staticpinProvider.setup2FAKey(userAccountVO)).thenReturn("345543");
        Mockito.when(userDaoMock.createForUpdate()).thenReturn(userVoMock);
        AccountManagerImpl.userTwoFactorAuthenticationProvidersMap = userTwoFactorAuthenticationProvidersMap;

        UserTwoFactorAuthenticationSetupResponse response = accountManagerImpl.setupUserTwoFactorAuthentication(cmd);

        Assert.assertEquals("345543", response.getSecretCode());
    }

    @Test
    public void testGetActiveUserAccountByEmail() {
        String email = "test@example.com";
        Long domainId = 1L;
        List<UserAccountVO> userAccountVOList = new ArrayList<>();
        UserAccountVO userAccountVO = new UserAccountVO();
        userAccountVOList.add(userAccountVO);
        Mockito.when(userAccountDaoMock.getUserAccountByEmail(email, domainId)).thenReturn(userAccountVOList);
        List<UserAccount> userAccounts = accountManagerImpl.getActiveUserAccountByEmail(email, domainId);
        Assert.assertEquals(userAccountVOList.size(), userAccounts.size());
        Assert.assertEquals(userAccountVOList.get(0), userAccounts.get(0));
    }

    @Test
    public void testDeleteWebhooksForAccount() {
        try (MockedStatic<ComponentContext> mockedComponentContext = Mockito.mockStatic(ComponentContext.class)) {
            WebhookHelper webhookHelper = Mockito.mock(WebhookHelper.class);
            Mockito.doNothing().when(webhookHelper).deleteWebhooksForAccount(Mockito.anyLong());
            mockedComponentContext.when(() -> ComponentContext.getDelegateComponentOfType(WebhookHelper.class))
                    .thenReturn(webhookHelper);
            accountManagerImpl.deleteWebhooksForAccount(1L);
        }
    }

    @Test
    public void testDeleteWebhooksForAccountNoBean() {
        try (MockedStatic<ComponentContext> mockedComponentContext = Mockito.mockStatic(ComponentContext.class)) {
            mockedComponentContext.when(() -> ComponentContext.getDelegateComponentOfType(WebhookHelper.class))
                    .thenThrow(NoSuchBeanDefinitionException.class);
            accountManagerImpl.deleteWebhooksForAccount(1L);
        }
    }

    @Test(expected = PermissionDeniedException.class)
    public void testValidateRoleChangeUnknownCaller() {
        Account account = Mockito.mock(Account.class);
        Mockito.when(account.getRoleId()).thenReturn(1L);
        Role role = Mockito.mock(Role.class);
        Mockito.when(role.getRoleType()).thenReturn(RoleType.Unknown);
        Account caller = Mockito.mock(Account.class);
        Mockito.when(caller.getRoleId()).thenReturn(2L);
        Mockito.when(roleService.findRole(2L)).thenReturn(role);
        accountManagerImpl.validateRoleChange(account, Mockito.mock(Role.class), caller);
    }

    @Test(expected = PermissionDeniedException.class)
    public void testValidateRoleChangeUnknownNewRole() {
        Account account = Mockito.mock(Account.class);
        Mockito.when(account.getRoleId()).thenReturn(1L);
        Role newRole = Mockito.mock(Role.class);
        Mockito.when(newRole.getRoleType()).thenReturn(RoleType.Unknown);
        Role callerRole = Mockito.mock(Role.class);
        Mockito.when(callerRole.getRoleType()).thenReturn(RoleType.DomainAdmin);
        Account caller = Mockito.mock(Account.class);
        Mockito.when(caller.getRoleId()).thenReturn(2L);
        Mockito.when(roleService.findRole(2L)).thenReturn(callerRole);
        accountManagerImpl.validateRoleChange(account, newRole, caller);
    }

    @Test
    public void testValidateRoleNewRoleSameCaller() {
        Account account = Mockito.mock(Account.class);
        Mockito.when(account.getRoleId()).thenReturn(1L);
        Role currentRole = Mockito.mock(Role.class);
        Mockito.when(currentRole.getRoleType()).thenReturn(RoleType.User);
        Mockito.when(roleService.findRole(1L)).thenReturn(currentRole);
        Role newRole = Mockito.mock(Role.class);
        Mockito.when(newRole.getRoleType()).thenReturn(RoleType.DomainAdmin);
        Role callerRole = Mockito.mock(Role.class);
        Mockito.when(callerRole.getRoleType()).thenReturn(RoleType.DomainAdmin);
        Account caller = Mockito.mock(Account.class);
        Mockito.when(caller.getRoleId()).thenReturn(2L);
        Mockito.when(roleService.findRole(2L)).thenReturn(callerRole);
        accountManagerImpl.validateRoleChange(account, newRole, caller);
    }

    @Test
    public void testValidateRoleCurrentRoleSameCaller() {
        Account account = Mockito.mock(Account.class);
        Mockito.when(account.getRoleId()).thenReturn(1L);
        Role accountRole = Mockito.mock(Role.class);
        Mockito.when(accountRole.getRoleType()).thenReturn(RoleType.DomainAdmin);
        Role newRole = Mockito.mock(Role.class);
        Mockito.when(newRole.getRoleType()).thenReturn(RoleType.User);
        Role callerRole = Mockito.mock(Role.class);
        Mockito.when(callerRole.getRoleType()).thenReturn(RoleType.DomainAdmin);
        Account caller = Mockito.mock(Account.class);
        Mockito.when(caller.getRoleId()).thenReturn(2L);
        Mockito.when(roleService.findRole(1L)).thenReturn(accountRole);
        Mockito.when(roleService.findRole(2L)).thenReturn(callerRole);
        accountManagerImpl.validateRoleChange(account, newRole, caller);
    }

    @Test(expected = PermissionDeniedException.class)
    public void testValidateRoleNewRoleHigherCaller() {
        Account account = Mockito.mock(Account.class);
        Mockito.when(account.getRoleId()).thenReturn(1L);
        Role newRole = Mockito.mock(Role.class);
        Mockito.when(newRole.getRoleType()).thenReturn(RoleType.Admin);
        Role callerRole = Mockito.mock(Role.class);
        Mockito.when(callerRole.getRoleType()).thenReturn(RoleType.DomainAdmin);
        Account caller = Mockito.mock(Account.class);
        Mockito.when(caller.getRoleId()).thenReturn(2L);
        Mockito.when(roleService.findRole(2L)).thenReturn(callerRole);
        accountManagerImpl.validateRoleChange(account, newRole, caller);
    }

    @Test
    public void testValidateRoleNewRoleLowerCaller() {
        Account account = Mockito.mock(Account.class);
        Mockito.when(account.getRoleId()).thenReturn(1L);
        Role newRole = Mockito.mock(Role.class);
        Mockito.when(newRole.getRoleType()).thenReturn(RoleType.User);
        Role accountRole = Mockito.mock(Role.class);
        Mockito.when(accountRole.getRoleType()).thenReturn(RoleType.User);
        Role callerRole = Mockito.mock(Role.class);
        Mockito.when(callerRole.getRoleType()).thenReturn(RoleType.DomainAdmin);
        Account caller = Mockito.mock(Account.class);
        Mockito.when(caller.getRoleId()).thenReturn(2L);
        Mockito.when(roleService.findRole(1L)).thenReturn(accountRole);
        Mockito.when(roleService.findRole(2L)).thenReturn(callerRole);
        accountManagerImpl.validateRoleChange(account, newRole, caller);
    }

    @Test(expected = PermissionDeniedException.class)
    public void testValidateRoleAdminCannotEscalateAdminFromNonRootDomain() {
        Account account = Mockito.mock(Account.class);
        Mockito.when(account.getRoleId()).thenReturn(1L);
        Mockito.when(account.getDomainId()).thenReturn(2L);
        Role newRole = Mockito.mock(Role.class);
        Mockito.when(newRole.getRoleType()).thenReturn(RoleType.Admin);
        Role accountRole = Mockito.mock(Role.class);
        Role callerRole = Mockito.mock(Role.class);
        Mockito.when(callerRole.getRoleType()).thenReturn(RoleType.Admin);
        Account caller = Mockito.mock(Account.class);
        Mockito.when(caller.getRoleId()).thenReturn(2L);
        Mockito.when(roleService.findRole(1L)).thenReturn(accountRole);
        Mockito.when(roleService.findRole(2L)).thenReturn(callerRole);
        accountManagerImpl.validateRoleChange(account, newRole, caller);
    }

    @Test
    public void checkIfAccountManagesProjectsTestNotThrowExceptionWhenTheAccountIsNotAProjectAdministrator() {
        long accountId = 1L;
        List<Long> managedProjectIds = new ArrayList<>();

        Mockito.when(_projectAccountDao.listAdministratedProjectIds(accountId)).thenReturn(managedProjectIds);
        accountManagerImpl.checkIfAccountManagesProjects(accountId);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void checkIfAccountManagesProjectsTestThrowExceptionWhenTheAccountIsAProjectAdministrator() {
        long accountId = 1L;
        List<Long> managedProjectIds = List.of(1L);

        Mockito.when(_projectAccountDao.listAdministratedProjectIds(accountId)).thenReturn(managedProjectIds);
        accountManagerImpl.checkIfAccountManagesProjects(accountId);
    }

    @Test
    public void testClearUser2FA_When2FADisabled_NoChanges() {
        UserAccount user = Mockito.mock(UserAccount.class);
        Mockito.when(user.isUser2faEnabled()).thenReturn(false);
        Mockito.when(user.getUser2faProvider()).thenReturn(null);
        UserAccount result = accountManagerImpl.clearUserTwoFactorAuthenticationInSetupStateOnLogin(user);
        Assert.assertSame(user, result);
        Mockito.verifyNoInteractions(userDetailsDaoMock, userAccountDaoMock);
    }

    @Test
    public void testClearUser2FA_When2FAInVerifiedState_NoChanges() {
        UserAccount user = Mockito.mock(UserAccount.class);
        Mockito.when(user.getId()).thenReturn(1L);
        Mockito.when(user.isUser2faEnabled()).thenReturn(true);
        UserDetailVO userDetail = new UserDetailVO();
        userDetail.setValue(UserAccountVO.Setup2FAstatus.VERIFIED.name());
        Mockito.when(userDetailsDaoMock.findDetail(1L, UserDetailVO.Setup2FADetail)).thenReturn(userDetail);
        UserAccount result = accountManagerImpl.clearUserTwoFactorAuthenticationInSetupStateOnLogin(user);
        Assert.assertSame(user, result);
        Mockito.verify(userDetailsDaoMock).findDetail(1L, UserDetailVO.Setup2FADetail);
        Mockito.verifyNoMoreInteractions(userDetailsDaoMock, userAccountDaoMock);
    }

    @Test
    public void testClearUser2FA_When2FAInSetupState_Disable2FA() {
        UserAccount user = Mockito.mock(UserAccount.class);
        Mockito.when(user.getId()).thenReturn(1L);
        Mockito.when(user.isUser2faEnabled()).thenReturn(true);
        UserDetailVO userDetail = new UserDetailVO();
        userDetail.setValue(UserAccountVO.Setup2FAstatus.ENABLED.name());
        UserAccountVO userAccountVO = new UserAccountVO();
        userAccountVO.setId(1L);
        Mockito.when(userDetailsDaoMock.findDetail(1L, UserDetailVO.Setup2FADetail)).thenReturn(userDetail);
        Mockito.when(userAccountDaoMock.findById(1L)).thenReturn(userAccountVO);
        UserAccount result = accountManagerImpl.clearUserTwoFactorAuthenticationInSetupStateOnLogin(user);
        Assert.assertNotNull(result);
        Assert.assertFalse(result.isUser2faEnabled());
        Assert.assertNull(result.getUser2faProvider());
        Mockito.verify(userDetailsDaoMock).findDetail(1L, UserDetailVO.Setup2FADetail);
        Mockito.verify(userDetailsDaoMock).remove(Mockito.anyLong());
        Mockito.verify(userAccountDaoMock).findById(1L);
        ArgumentCaptor<UserAccountVO> captor = ArgumentCaptor.forClass(UserAccountVO.class);
        Mockito.verify(userAccountDaoMock).update(Mockito.eq(1L), captor.capture());
        UserAccountVO updatedUser = captor.getValue();
        Assert.assertFalse(updatedUser.isUser2faEnabled());
        Assert.assertNull(updatedUser.getUser2faProvider());
        Assert.assertNull(updatedUser.getKeyFor2fa());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testAssertUserNotAlreadyInAccount_UserExistsInAccount() {
        User existingUser = new UserVO();
        existingUser.setUsername("testuser");
        existingUser.setAccountId(1L);

        Account newAccount = Mockito.mock(Account.class);
        Mockito.when(newAccount.getId()).thenReturn(1L);

        AccountVO existingAccount = Mockito.mock(AccountVO.class);
        Mockito.when(existingAccount.getUuid()).thenReturn("existing-account-uuid");
        Mockito.when(existingAccount.getAccountName()).thenReturn("existing-account");

        Mockito.when(_accountDao.findById(1L)).thenReturn(existingAccount);

        accountManagerImpl.assertUserNotAlreadyInAccount(existingUser, newAccount);
    }

    @Test
    public void testAssertUserNotAlreadyInAccount_UserExistsInDiffAccount() {
        User existingUser = new UserVO();
        existingUser.setUsername("testuser");
        existingUser.setAccountId(2L);

        Account newAccount = Mockito.mock(Account.class);
        Mockito.when(newAccount.getId()).thenReturn(1L);

        accountManagerImpl.assertUserNotAlreadyInAccount(existingUser, newAccount);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testAssertUserNotAlreadyInDomain_UserExistsInDomain() {
        User existingUser = new UserVO();
        existingUser.setUsername("testuser");
        existingUser.setAccountId(1L);

        Account originalAccount = Mockito.mock(Account.class);
        Mockito.when(originalAccount.getDomainId()).thenReturn(1L);

        AccountVO existingAccount = Mockito.mock(AccountVO.class);
        Mockito.when(existingAccount.getDomainId()).thenReturn(1L);
        Mockito.when(existingAccount.getUuid()).thenReturn("existing-account-uuid");
        Mockito.when(existingAccount.getAccountName()).thenReturn("existing-account");

        DomainVO existingDomain = Mockito.mock(DomainVO.class);
        Mockito.when(existingDomain.getUuid()).thenReturn("existing-domain-uuid");
        Mockito.when(existingDomain.getName()).thenReturn("existing-domain");

        Mockito.when(_accountDao.findById(1L)).thenReturn(existingAccount);
        Mockito.when(_domainDao.findById(1L)).thenReturn(existingDomain);

        accountManagerImpl.assertUserNotAlreadyInDomain(existingUser, originalAccount);
    }

    @Test
    public void testAssertUserNotAlreadyInDomain_UserExistsInDiffDomain() {
        User existingUser = new UserVO();
        existingUser.setUsername("testuser");
        existingUser.setAccountId(1L);

        Account originalAccount = Mockito.mock(Account.class);
        Mockito.when(originalAccount.getDomainId()).thenReturn(1L);

        AccountVO existingAccount = Mockito.mock(AccountVO.class);
        Mockito.when(existingAccount.getDomainId()).thenReturn(2L);

        Mockito.when(_accountDao.findById(1L)).thenReturn(existingAccount);

        accountManagerImpl.assertUserNotAlreadyInDomain(existingUser, originalAccount);
    }

    @Test
    public void testCheckCallerRoleTypeAllowedToUpdateUserSameAccount() {
        Mockito.lenient().when(accountManagerImpl.getCurrentCallingAccount()).thenReturn(accountMock);
        Mockito.lenient().when(accountManagerImpl.getRoleType(Mockito.eq(accountMock))).thenReturn(RoleType.DomainAdmin);

        accountManagerImpl.checkCallerRoleTypeAllowedForUserOrAccountOperations(accountMock, userVoMock);
    }

    @Test(expected = PermissionDeniedException.class)
    public void testCheckCallerRoleTypeAllowedToUpdateUserLowerAccountRoleType() {
        Account callingAccount = Mockito.mock(Account.class);
        Mockito.lenient().when(callingAccount.getAccountId()).thenReturn(2L);
        Mockito.lenient().doReturn(callingAccount).when(accountManagerImpl).getAccount(2L);
        Mockito.lenient().when(accountManagerImpl.getCurrentCallingAccount()).thenReturn(callingAccount);
        Mockito.lenient().when(accountManagerImpl.getRoleType(Mockito.eq(callingAccount))).thenReturn(RoleType.DomainAdmin);
        Mockito.lenient().when(accountManagerImpl.getRoleType(Mockito.eq(accountMock))).thenReturn(RoleType.Admin);
        accountManagerImpl.checkCallerRoleTypeAllowedForUserOrAccountOperations(accountMock, userVoMock);
    }

    @Test
    public void testcheckCallerApiPermissionsForUserOperationsRootAdminSameCaller() {
        Mockito.lenient().when(accountManagerImpl.getCurrentCallingAccount()).thenReturn(accountMock);
        Mockito.when(accountMock.getId()).thenReturn(2L);
        Mockito.doReturn(true).when(accountManagerImpl).isRootAdmin(2L);
        accountManagerImpl.checkCallerApiPermissionsForUserOrAccountOperations(accountMock);
    }

    @Test(expected = PermissionDeniedException.class)
    public void testcheckCallerApiPermissionsForUserOperationsRootAdminDifferentAccount() {
        Mockito.lenient().when(accountManagerImpl.getCurrentCallingAccount()).thenReturn(callingAccount);
        Mockito.lenient().when(callingAccount.getAccountId()).thenReturn(3L);
        Mockito.lenient().doReturn(callingAccount).when(accountManagerImpl).getAccount(3L);
        Mockito.lenient().doReturn(false).when(accountManagerImpl).isRootAdmin(3L);

        Mockito.when(accountMock.getAccountId()).thenReturn(2L);
        Mockito.doReturn(true).when(accountManagerImpl).isRootAdmin(2L);

        accountManagerImpl.checkCallerApiPermissionsForUserOrAccountOperations(accountMock);
    }

    @Test
    public void testcheckCallerApiPermissionsForUserOperationsAllowedApis() {
        Mockito.lenient().when(accountManagerImpl.getCurrentCallingAccount()).thenReturn(callingAccount);
        Mockito.lenient().when(callingAccount.getAccountId()).thenReturn(3L);
        Mockito.lenient().doReturn(callingAccount).when(accountManagerImpl).getAccount(3L);
        Mockito.lenient().doReturn(false).when(accountManagerImpl).isRootAdmin(3L);

        Mockito.when(accountMock.getAccountId()).thenReturn(2L);
        Mockito.doReturn(false).when(accountManagerImpl).isRootAdmin(2L);

        Mockito.lenient().doNothing().when(accountManagerImpl).checkRoleEscalation(callingAccount, accountMock);

        accountManagerImpl.checkCallerApiPermissionsForUserOrAccountOperations(accountMock);
    }

    @Test(expected = PermissionDeniedException.class)
    public void testcheckCallerApiPermissionsForUserOperationsNotAllowedApis() {
        Mockito.lenient().when(accountManagerImpl.getCurrentCallingAccount()).thenReturn(callingAccount);
        Mockito.lenient().when(callingAccount.getAccountId()).thenReturn(3L);
        Mockito.lenient().doReturn(callingAccount).when(accountManagerImpl).getAccount(3L);
        Mockito.lenient().doReturn(false).when(accountManagerImpl).isRootAdmin(3L);

        Mockito.when(accountMock.getAccountId()).thenReturn(2L);
        Mockito.doReturn(false).when(accountManagerImpl).isRootAdmin(2L);

        Mockito.lenient().doThrow(PermissionDeniedException.class).when(accountManagerImpl).checkRoleEscalation(callingAccount, accountMock);

        accountManagerImpl.checkCallerApiPermissionsForUserOrAccountOperations(accountMock);
    }
}
