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
package com.cloud.api;

import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Set;

import javax.inject.Inject;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ApiServerService;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.auth.APIAuthenticationManager;
import org.apache.cloudstack.api.auth.APIAuthenticationType;
import org.apache.cloudstack.api.auth.APIAuthenticator;
import org.apache.cloudstack.api.command.user.consoleproxy.CreateConsoleEndpointCmd;
import org.apache.cloudstack.api.command.user.gui.theme.ListGuiThemesCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.managed.context.ManagedContext;
import org.apache.cloudstack.utils.consoleproxy.ConsoleAccessUtils;
import org.apache.commons.collections.MapUtils;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.commons.lang3.EnumUtils;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.context.support.SpringBeanAutowiringSupport;

import com.cloud.api.auth.ListUserTwoFactorAuthenticatorProvidersCmd;
import com.cloud.api.auth.SetupUserTwoFactorAuthenticationCmd;
import com.cloud.api.auth.ValidateUserTwoFactorAuthenticationCodeCmd;
import com.cloud.projects.Project;
import com.cloud.projects.dao.ProjectDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManagerImpl;
import com.cloud.user.AccountService;
import com.cloud.user.User;
import com.cloud.user.UserAccount;

import com.cloud.utils.HttpUtils;
import com.cloud.utils.HttpUtils.ApiSessionKeySameSite;
import com.cloud.utils.HttpUtils.ApiSessionKeyCheckOption;
import com.cloud.utils.StringUtils;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.net.NetUtils;

@Component("apiServlet")
public class ApiServlet extends HttpServlet {
    protected static Logger LOGGER = LogManager.getLogger(ApiServlet.class);
    private static final Logger ACCESSLOGGER = LogManager.getLogger("apiserver." + ApiServlet.class.getName());
    private static final String REPLACEMENT = "_";
    private static final String LOGGER_REPLACEMENTS = "[\n\r\t]";
    private static final Pattern GET_REQUEST_COMMANDS = Pattern.compile("^(get|list|query|find)(\\w+)+$");
    private static final HashSet<String> GET_REQUEST_COMMANDS_LIST = new HashSet<>(Set.of("isaccountallowedtocreateofferingswithtags",
            "readyforshutdown", "cloudianisenabled", "quotabalance", "quotasummary", "quotatarifflist", "quotaisenabled", "quotastatement", "verifyoauthcodeandgetuser"));
    private static final HashSet<String> POST_REQUESTS_TO_DISABLE_LOGGING = new HashSet<>(Set.of(
            "login",
            "oauthlogin",
            "createaccount",
            "createuser",
            "updateuser",
            "forgotpassword",
            "resetpassword",
            "importrole",
            "updaterolepermission",
            "updateprojectrolepermission",
            "createstoragepool",
            "addhost",
            "updatehostpassword",
            "addcluster",
            "addvmwaredc",
            "configureoutofbandmanagement",
            "uploadcustomcertificate",
            "addciscovnmcresource",
            "addnetscalerloadbalancer",
            "createtungstenfabricprovider",
            "addnsxcontroller",
            "configtungstenfabricservice",
            "createnetworkacl",
            "updatenetworkaclitem",
            "quotavalidateactivationrule",
            "quotatariffupdate",
            "listandswitchsamlaccount",
            "uploadresourceicon"
    ));

    @Inject
    ApiServerService apiServer;
    @Inject
    AccountService accountMgr;
    @Inject
    EntityManager entityMgr;
    @Inject
    ManagedContext managedContext;
    @Inject
    APIAuthenticationManager authManager;
    @Inject
    private ProjectDao projectDao;

    public ApiServlet() {
    }

    @Override
    public void init(final ServletConfig config) throws ServletException {
        SpringBeanAutowiringSupport.processInjectionBasedOnServletContext(this, config.getServletContext());
    }

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) {
        processRequest(req, resp);
    }

    @Override
    protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) {
        processRequest(req, resp);
    }

    /**
     * For HTTP GET requests, it seems that HttpServletRequest.getParameterMap() actually tries
     * to unwrap URL encoded content from ISO-9959-1.
     * After failed in using setCharacterEncoding() to control it, end up with following hacking:
     * for all GET requests, we will override it with our-own way of UTF-8 based URL decoding.
     * @param req request containing parameters
     * @param params output of "our" map of parameters/values
     */
    void utf8Fixup(final HttpServletRequest req, final Map<String, Object[]> params) {
        if (req.getQueryString() == null) {
            return;
        }

        final String[] paramsInQueryString = req.getQueryString().split("&");
        if (paramsInQueryString != null) {
            for (final String param : paramsInQueryString) {
                final String[] paramTokens = param.split("=", 2);
                if (paramTokens.length == 2) {
                    String name = decodeUtf8(paramTokens[0]);
                    String value = decodeUtf8(paramTokens[1]);
                    params.put(name, new String[] {value});
                } else {
                    LOGGER.debug("Invalid parameter in URL found. param: " + param);
                }
            }
        }
    }

    private String decodeUtf8(final String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (final UnsupportedEncodingException e) {
            //should never happen
            return null;
        }
    }

    private void processRequest(final HttpServletRequest req, final HttpServletResponse resp) {
        managedContext.runWithContext(new Runnable() {
            @Override
            public void run() {
                processRequestInContext(req, resp);
            }
        });
    }

    private void checkSingleQueryParameterValue(Map<String, String[]> params) {
        params.forEach((k, v) -> {
            if (v.length > 1) {
                String message = String.format("Query parameter '%s' has multiple values %s. Only the last value will be respected." +
                    "It is advised to pass only a single parameter", k, Arrays.toString(v));
                LOGGER.warn(message);
            }
        });

    }

    void processRequestInContext(final HttpServletRequest req, final HttpServletResponse resp) {
        InetAddress remoteAddress = null;
        try {
            remoteAddress = getClientAddress(req);
        } catch (UnknownHostException e) {
            LOGGER.warn("UnknownHostException when trying to lookup remote IP-Address. This should never happen. Blocking request.", e);
            final String response = apiServer.getSerializedApiError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "UnknownHostException when trying to lookup remote IP-Address", null,
                    HttpUtils.RESPONSE_TYPE_XML);
            HttpUtils.writeHttpResponse(resp, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    HttpUtils.RESPONSE_TYPE_XML, ApiServer.JSONcontentType.value());
            return;
        }

        final StringBuilder auditTrailSb = new StringBuilder(128);
        auditTrailSb.append(" ").append(remoteAddress.getHostAddress());
        auditTrailSb.append(" -- ").append(req.getMethod()).append(' ');
        // get the response format since we'll need it in a couple of places
        String responseType = HttpUtils.RESPONSE_TYPE_XML;
        final Map<String, Object[]> params = new HashMap<String, Object[]>();
        Map<String, String[]> reqParams = req.getParameterMap();
        checkSingleQueryParameterValue(reqParams);
        params.putAll(reqParams);

        utf8Fixup(req, params);

        final Object[] commandObj = params.get(ApiConstants.COMMAND);
        final String command = commandObj == null ? null : (String) commandObj[0];

        // logging the request start and end in management log for easy debugging
        String reqStr = "";
        String cleanQueryString = StringUtils.cleanString(req.getQueryString());
        if (LOGGER.isDebugEnabled()) {
            reqStr = auditTrailSb.toString() + " " + cleanQueryString;
            if (req.getMethod().equalsIgnoreCase("POST") && org.apache.commons.lang3.StringUtils.isNotBlank(command)) {
                if (!POST_REQUESTS_TO_DISABLE_LOGGING.contains(command.toLowerCase()) && !reqParams.containsKey(ApiConstants.USER_DATA)) {
                    String cleanParamsString = getCleanParamsString(reqParams);
                    if (org.apache.commons.lang3.StringUtils.isNotBlank(cleanParamsString)) {
                        reqStr += "\n" + cleanParamsString;
                    }
                } else {
                    reqStr += " " + command;
                }
            }
            LOGGER.debug("===START=== " + reqStr);
        }

        try {
            resp.setContentType(HttpUtils.XML_CONTENT_TYPE);

            HttpSession session = req.getSession(false);
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace(String.format("session found: %s", session));
            }
            final Object[] responseTypeParam = params.get(ApiConstants.RESPONSE);
            if (responseTypeParam != null) {
                responseType = (String)responseTypeParam[0];
            }

            final Object[] userObj = params.get(ApiConstants.USERNAME);
            String username = userObj == null ? null : (String)userObj[0];
            if (LOGGER.isTraceEnabled()) {
                String logCommand = saveLogString(command);
                String logName = saveLogString(username);
                LOGGER.trace(String.format("command %s processing for user \"%s\"",
                        logCommand,
                        logName));
            }

            if (command != null && !command.equals(ValidateUserTwoFactorAuthenticationCodeCmd.APINAME)) {

                APIAuthenticator apiAuthenticator = authManager.getAPIAuthenticator(command);
                if (apiAuthenticator != null) {
                    auditTrailSb.append("command=");
                    auditTrailSb.append(command);

                    int httpResponseCode = HttpServletResponse.SC_OK;
                    String responseString = null;

                    if (apiAuthenticator.getAPIType() == APIAuthenticationType.LOGIN_API) {
                        if (session != null) {
                            invalidateHttpSession(session, "invalidating session for login call");
                        }
                        session = req.getSession(true);

                        if (ApiServer.EnableSecureSessionCookie.value()) {
                            resp.setHeader("SET-COOKIE", String.format("JSESSIONID=%s;Secure;HttpOnly;Path=/client", session.getId()));
                            if (LOGGER.isDebugEnabled()) {
                                LOGGER.debug("Session cookie is marked secure!");
                            }
                        }
                    }

                    try {
                        if (LOGGER.isTraceEnabled()) {
                            LOGGER.trace(String.format("apiAuthenticator.authenticate(%s, params[%d], %s, %s, %s, %s, %s,%s)",
                                    saveLogString(command), params.size(), session.getId(), remoteAddress.getHostAddress(), saveLogString(responseType), "auditTrailSb", "req", "resp"));
                        }
                        responseString = apiAuthenticator.authenticate(command, params, session, remoteAddress, responseType, auditTrailSb, req, resp);
                        if (session != null && session.getAttribute(ApiConstants.SESSIONKEY) != null) {
                            String sameSite = getApiSessionKeySameSite();
                            resp.addHeader("SET-COOKIE", String.format("%s=%s;HttpOnly;%s", ApiConstants.SESSIONKEY, session.getAttribute(ApiConstants.SESSIONKEY), sameSite));
                        }
                    } catch (ServerApiException e) {
                        httpResponseCode = e.getErrorCode().getHttpCode();
                        responseString = e.getMessage();
                        LOGGER.debug("Authentication failure: " + e.getMessage());
                    }

                    if (apiAuthenticator.getAPIType() == APIAuthenticationType.LOGOUT_API) {
                        if (session == null) {
                            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "Session not found for the logout process.");
                        }

                        final Long userId = (Long) session.getAttribute("userid");
                        final Account account = (Account) session.getAttribute("accountobj");
                        Long accountId = null;
                        if (account != null) {
                            accountId = account.getId();
                        }
                        auditTrailSb.insert(0, "(userId=" + userId + " accountId=" + accountId + " sessionId=" + session.getId() + ")");
                        if (userId != null) {
                            apiServer.logoutUser(userId);
                        }
                        invalidateHttpSession(session, "invalidating session after logout call");

                        final Cookie[] cookies = req.getCookies();
                        if (cookies != null) {
                            for (final Cookie cookie : cookies) {
                                cookie.setValue("");
                                cookie.setMaxAge(0);
                                resp.addCookie(cookie);
                            }
                        }
                    }
                    HttpUtils.writeHttpResponse(resp, responseString, httpResponseCode, responseType, ApiServer.JSONcontentType.value());
                    return;
                }
            } else {
                LOGGER.trace("no command available");
            }
            auditTrailSb.append(cleanQueryString);
            final boolean isNew = ((session == null) ? true : session.isNew());

            // Initialize an empty context and we will update it after we have verified the request below,
            // we no longer rely on web-session here, verifyRequest will populate user/account information
            // if a API key exists

            if (isNew && LOGGER.isTraceEnabled()) {
                LOGGER.trace(String.format("new session: %s", session));
            }

            if (!isNew && (command.equalsIgnoreCase(ValidateUserTwoFactorAuthenticationCodeCmd.APINAME) || (!skip2FAcheckForAPIs(command) && !skip2FAcheckForUser(session)))) {
                LOGGER.debug("Verifying two factor authentication");
                boolean success = verify2FA(session, command, auditTrailSb, params, remoteAddress, responseType, req, resp);
                if (!success) {
                    LOGGER.debug("Verification of two factor authentication failed");
                    return;
                }
            }

            if (apiServer.isPostRequestsAndTimestampsEnforced() && !isStateChangingCommandUsingPOST(command, req.getMethod(), params)) {
                String errorText = String.format("State changing command %s needs to be sent using POST request", command);
                if (command.equalsIgnoreCase("updateConfiguration") && params.containsKey("name")) {
                    errorText = String.format("Changes for configuration %s needs to be sent using POST request", params.get("name")[0]);
                }
                auditTrailSb.append(" " + HttpServletResponse.SC_BAD_REQUEST + " " + errorText);
                final String serializedResponse =
                        apiServer.getSerializedApiError(new ServerApiException(ApiErrorCode.BAD_REQUEST, errorText), params,
                                responseType);
                HttpUtils.writeHttpResponse(resp, serializedResponse, HttpServletResponse.SC_BAD_REQUEST, responseType, ApiServer.JSONcontentType.value());
                return;
            }

            Long userId = null;
            if (!isNew) {
                userId = (Long)session.getAttribute("userid");
                final String account = (String) session.getAttribute("account");
                final Object accountObj = session.getAttribute("accountobj");
                if (account != null) {
                    if (invalidateHttpSessionIfNeeded(req, resp, auditTrailSb, responseType, params, session, account)) return;
                } else {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("no account, this request will be validated through apikey(%s)/signature");
                    }
                }

                if (! requestChecksoutAsSane(resp, auditTrailSb, responseType, params, session, command, userId, account, accountObj))
                    return;
            } else {
                CallContext.register(accountMgr.getSystemUser(), accountMgr.getSystemAccount());
            }
            setProjectContext(params);
            setGuiThemeParameterIfApiCallIsUnauthenticated(userId, command, req, params);

            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace(String.format("verifying request for user %s from %s with %d parameters",
                        userId, remoteAddress.getHostAddress(), params.size()));
            }
            if (apiServer.verifyRequest(params, userId, remoteAddress)) {
                auditTrailSb.insert(0, "(userId=" + CallContext.current().getCallingUserId() + " accountId=" + CallContext.current().getCallingAccount().getId() +
                        " sessionId=" + (session != null ? session.getId() : null) + ")");

                // Add the HTTP method (GET/POST/PUT/DELETE) as well into the params map.
                params.put("httpmethod", new String[]{req.getMethod()});
                setProjectContext(params);
                setClientAddressForConsoleEndpointAccess(command, params, req);
                final String response = apiServer.handleRequest(params, responseType, auditTrailSb);
                HttpUtils.writeHttpResponse(resp, response != null ? response : "", HttpServletResponse.SC_OK, responseType, ApiServer.JSONcontentType.value());
            } else {
                if (session != null) {
                    invalidateHttpSession(session, String.format("request verification failed for %s from %s", userId, remoteAddress.getHostAddress()));
                }

                auditTrailSb.append(" " + HttpServletResponse.SC_UNAUTHORIZED + " " + "unable to verify user credentials and/or request signature");
                final String serializedResponse =
                        apiServer.getSerializedApiError(HttpServletResponse.SC_UNAUTHORIZED, "unable to verify user credentials and/or request signature", params,
                                responseType);
                HttpUtils.writeHttpResponse(resp, serializedResponse, HttpServletResponse.SC_UNAUTHORIZED, responseType, ApiServer.JSONcontentType.value());

            }
        } catch (final ServerApiException se) {
            final String serializedResponseText = apiServer.getSerializedApiError(se, params, responseType);
            resp.setHeader("X-Description", se.getDescription());
            HttpUtils.writeHttpResponse(resp, serializedResponseText, se.getErrorCode().getHttpCode(), responseType, ApiServer.JSONcontentType.value());
            auditTrailSb.append(" " + se.getErrorCode() + " " + se.getDescription());
        } catch (final Exception ex) {
            LOGGER.error("unknown exception writing api response", ex);
            auditTrailSb.append(" unknown exception writing api response");
        } finally {
            ACCESSLOGGER.info(auditTrailSb.toString());
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("===END=== " + reqStr);
            }
            // cleanup user context to prevent from being peeked in other request context
            CallContext.unregister();
        }
    }

    public static String getApiSessionKeySameSite() {
        ApiSessionKeySameSite sameSite = EnumUtils.getEnumIgnoreCase(ApiSessionKeySameSite.class,
                ApiServer.ApiSessionKeyCookieSameSiteSetting.value(), ApiSessionKeySameSite.Lax);
        switch (sameSite) {
            case Strict:
                return "SameSite=Strict";
            case NoneAndSecure:
                return "SameSite=None;Secure";
            case Null:
                return "";
            case Lax:
            default:
                return "SameSite=Lax";
        }
    }

    private void setGuiThemeParameterIfApiCallIsUnauthenticated(Long userId, String command, HttpServletRequest req, Map<String, Object[]> params) {
        String listGuiThemesApiName = ListGuiThemesCmd.class.getAnnotation(APICommand.class).name();

        if (userId != null || !listGuiThemesApiName.equalsIgnoreCase(command)) {
            return;
        }

        String serverName = req.getServerName();
        LOGGER.info("Unauthenticated call to {} API, thus, the `commonName` parameter will be inferred as {}.", listGuiThemesApiName, serverName);
        params.put(ApiConstants.COMMON_NAME, new String[]{serverName});
    }


    private boolean checkIfAuthenticatorIsOf2FA(String command) {
        boolean verify2FA = false;
        APIAuthenticator apiAuthenticator = authManager.getAPIAuthenticator(command);
        if (apiAuthenticator != null && apiAuthenticator.getAPIType().equals(APIAuthenticationType.LOGIN_2FA_API)) {
            verify2FA = true;
        } else {
            verify2FA = false;
        }
        return verify2FA;
    }

    private boolean isStateChangingCommandUsingPOST(String command, String method, Map<String, Object[]> params) {
        if (command == null || (!GET_REQUEST_COMMANDS.matcher(command.toLowerCase()).matches() && !GET_REQUEST_COMMANDS_LIST.contains(command.toLowerCase())
                && !command.equalsIgnoreCase("updateConfiguration") && !method.equals("POST"))) {
            return false;
        }
        return !command.equalsIgnoreCase("updateConfiguration") || method.equals("POST") || (params.containsKey("name")
                && params.get("name")[0].toString().equalsIgnoreCase(ApiServer.EnforcePostRequestsAndTimestamps.key()));
    }

    protected boolean skip2FAcheckForAPIs(String command) {
        boolean skip2FAcheck = false;

        if (command.equalsIgnoreCase(ApiConstants.LIST_IDPS)
                || command.equalsIgnoreCase(ApiConstants.LIST_APIS)
                || command.equalsIgnoreCase(ListUserTwoFactorAuthenticatorProvidersCmd.APINAME)
                || command.equalsIgnoreCase(SetupUserTwoFactorAuthenticationCmd.APINAME)) {
            skip2FAcheck = true;
        }
        return skip2FAcheck;
    }

    protected boolean skip2FAcheckForUser(HttpSession session) {
        boolean skip2FAcheck = false;
        Long userId = (Long) session.getAttribute("userid");
        boolean is2FAverified = (boolean) session.getAttribute(ApiConstants.IS_2FA_VERIFIED);
        if (is2FAverified) {
            LOGGER.debug(String.format("Two factor authentication is already verified for the user %d, so skipping", userId));
            skip2FAcheck = true;
        } else {
            UserAccount userAccount = accountMgr.getUserAccountById(userId);
            boolean is2FAenabled = userAccount.isUser2faEnabled();
            if (is2FAenabled) {
                skip2FAcheck = false;
            } else {
                Long domainId = userAccount.getDomainId();
                boolean is2FAmandated = Boolean.TRUE.equals(AccountManagerImpl.enableUserTwoFactorAuthentication.valueIn(domainId)) && Boolean.TRUE.equals(AccountManagerImpl.mandateUserTwoFactorAuthentication.valueIn(domainId));
                if (is2FAmandated) {
                    skip2FAcheck = false;
                } else {
                    skip2FAcheck = true;
                }
            }
        }
        return skip2FAcheck;
    }

    protected boolean verify2FA(HttpSession session, String command, StringBuilder auditTrailSb, Map<String, Object[]> params,
                                InetAddress remoteAddress, String responseType, HttpServletRequest req, HttpServletResponse resp) {
        boolean verify2FA = false;
        if (command.equals(ValidateUserTwoFactorAuthenticationCodeCmd.APINAME)) {
            APIAuthenticator apiAuthenticator = authManager.getAPIAuthenticator(command);
            if (apiAuthenticator != null) {
                String responseString = apiAuthenticator.authenticate(command, params, session, remoteAddress, responseType, auditTrailSb, req, resp);
                session.setAttribute(ApiConstants.IS_2FA_VERIFIED, true);
                HttpUtils.writeHttpResponse(resp, responseString, HttpServletResponse.SC_OK, responseType, ApiServer.JSONcontentType.value());
                verify2FA = true;
            } else {
                LOGGER.error("Cannot find API authenticator while verifying 2FA");
                auditTrailSb.append(" Cannot find API authenticator while verifying 2FA");
                verify2FA = false;
            }
        } else {
            // invalidate the session
            Long userId = (Long) session.getAttribute("userid");
            UserAccount userAccount = accountMgr.getUserAccountById(userId);
            boolean is2FAenabled = userAccount.isUser2faEnabled();
            String keyFor2fa = userAccount.getKeyFor2fa();
            String providerFor2fa = userAccount.getUser2faProvider();
            String errorMsg;
            if (is2FAenabled) {
                if (org.apache.commons.lang3.StringUtils.isEmpty(keyFor2fa) || org.apache.commons.lang3.StringUtils.isEmpty(providerFor2fa)) {
                    errorMsg = "Two factor authentication is mandated by admin, user needs to setup 2FA using setupUserTwoFactorAuthentication API and" +
                            " then verify 2FA using validateUserTwoFactorAuthenticationCode API before calling other APIs. Existing session is invalidated.";
                } else {
                    errorMsg = "Two factor authentication 2FA is enabled but not verified, please verify 2FA using validateUserTwoFactorAuthenticationCode API before calling other APIs. Existing session is invalidated.";
                }
            } else {
                // when (is2FAmandated) is true
                errorMsg = "Two factor authentication is mandated by admin, user needs to setup 2FA using setupUserTwoFactorAuthentication API and" +
                        " then verify 2FA using validateUserTwoFactorAuthenticationCode API before calling other APIs. Existing session is invalidated.";
            }
            LOGGER.error(errorMsg);

            invalidateHttpSession(session, String.format("Unable to process the API request for %s from %s due to %s", userId, remoteAddress.getHostAddress(), errorMsg));
            auditTrailSb.append(" " + ApiErrorCode.UNAUTHORIZED2FA + " " + errorMsg);
            final String serializedResponse = apiServer.getSerializedApiError(ApiErrorCode.UNAUTHORIZED2FA.getHttpCode(), "Unable to process the API request due to :" + errorMsg, params, responseType);
            HttpUtils.writeHttpResponse(resp, serializedResponse, ApiErrorCode.UNAUTHORIZED2FA.getHttpCode(), responseType, ApiServer.JSONcontentType.value());
            verify2FA = false;
        }

        return verify2FA;
    }
    protected void setClientAddressForConsoleEndpointAccess(String command, Map<String, Object[]> params, HttpServletRequest req) throws UnknownHostException {
        if (org.apache.commons.lang3.StringUtils.isNotBlank(command) &&
                command.equalsIgnoreCase(BaseCmd.getCommandNameByClass(CreateConsoleEndpointCmd.class))) {
            InetAddress addr = getClientAddress(req);
            String clientAddress = addr != null ? addr.getHostAddress() : null;
            params.put(ConsoleAccessUtils.CLIENT_INET_ADDRESS_KEY, new String[] {clientAddress});
        }
    }

    @Nullable
    private String saveLogString(String stringToLog) {
        return stringToLog == null ? null : stringToLog.replace(LOGGER_REPLACEMENTS, REPLACEMENT);
    }

    /**
     * Do a sanity check here to make sure the user hasn't already been deleted
     */
    private boolean requestChecksoutAsSane(HttpServletResponse resp, StringBuilder auditTrailSb, String responseType, Map<String, Object[]> params, HttpSession session, String command, Long userId, String account, Object accountObj) {
        if ((userId != null) && (account != null) && (accountObj != null) && apiServer.verifyUser(userId)) {
            if (command == null) {
                LOGGER.info("missing command, ignoring request...");
                auditTrailSb.append(" " + HttpServletResponse.SC_BAD_REQUEST + " " + "no command specified");
                final String serializedResponse = apiServer.getSerializedApiError(HttpServletResponse.SC_BAD_REQUEST, "no command specified", params, responseType);
                HttpUtils.writeHttpResponse(resp, serializedResponse, HttpServletResponse.SC_BAD_REQUEST, responseType, ApiServer.JSONcontentType.value());
                return true;
            }
            final User user = entityMgr.findById(User.class, userId);
            CallContext.register(user, (Account) accountObj);
        } else {
            invalidateHttpSession(session, "Invalidate the session to ensure we won't allow a request across management server restarts if the userId was serialized to the stored session");

            auditTrailSb.append(" " + HttpServletResponse.SC_UNAUTHORIZED + " " + "unable to verify user credentials");
            final String serializedResponse =
                    apiServer.getSerializedApiError(HttpServletResponse.SC_UNAUTHORIZED, "unable to verify user credentials", params, responseType);
            HttpUtils.writeHttpResponse(resp, serializedResponse, HttpServletResponse.SC_UNAUTHORIZED, responseType, ApiServer.JSONcontentType.value());
            return false;
        }
        return true;
    }

    private boolean invalidateHttpSessionIfNeeded(HttpServletRequest req, HttpServletResponse resp, StringBuilder auditTrailSb, String responseType, Map<String, Object[]> params, HttpSession session, String account) {
        ApiSessionKeyCheckOption sessionKeyCheckOption = EnumUtils.getEnumIgnoreCase(ApiSessionKeyCheckOption.class,
                ApiServer.ApiSessionKeyCheckLocations.value(), ApiSessionKeyCheckOption.CookieAndParameter);
        if (!HttpUtils.validateSessionKey(session, params, req.getCookies(), ApiConstants.SESSIONKEY, sessionKeyCheckOption)) {
            String msg = String.format("invalidating session %s for account %s", session.getId(), account);
            invalidateHttpSession(session, msg);
            auditTrailSb.append(" " + HttpServletResponse.SC_UNAUTHORIZED + " " + "unable to verify user credentials");
            final String serializedResponse =
                    apiServer.getSerializedApiError(HttpServletResponse.SC_UNAUTHORIZED, "unable to verify user credentials", params, responseType);
            HttpUtils.writeHttpResponse(resp, serializedResponse, HttpServletResponse.SC_UNAUTHORIZED, responseType, ApiServer.JSONcontentType.value());
            return true;
        }
        return false;
    }

    public static void invalidateHttpSession(HttpSession session, String msg) {
        try {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace(msg);
            }
            session.invalidate();
        } catch (final IllegalStateException ise) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace(String.format("failed to invalidate session %s", session.getId()));
            }
        }
    }

    private void setProjectContext(Map<String, Object[]> requestParameters) {
        final String[] command = (String[])requestParameters.get(ApiConstants.COMMAND);
        if (command == null) {
            LOGGER.info("missing command, ignoring request...");
            return;
        }

        final String commandName = command[0];
        CallContext.current().setApiName(commandName);
        for (Map.Entry<String, Object[]> entry: requestParameters.entrySet()) {
            if (entry.getKey().equals(ApiConstants.PROJECT_ID) || isSpecificAPI(commandName)) {
                String projectId = null;
                if (isSpecificAPI(commandName)) {
                    projectId = String.valueOf(requestParameters.entrySet().stream()
                            .filter(e -> e.getKey().equals(ApiConstants.ID))
                            .map(Map.Entry::getValue).findFirst().get()[0]);
                } else {
                    projectId = String.valueOf(entry.getValue()[0]);
                }
                Project project = projectDao.findByUuid(projectId);
                if (project != null) {
                    CallContext.current().setProject(project);
                }
            }
        }
    }

    private boolean isSpecificAPI(String commandName) {
        List<String> commands = Arrays.asList("suspendProject", "updateProject", "activateProject", "deleteProject");
        if (commands.contains(commandName)) {
            return true;
        }
        return false;
    }
    boolean doUseForwardHeaders() {
        return Boolean.TRUE.equals(ApiServer.useForwardHeader.value());
    }

    String[] proxyNets() {
        return ApiServer.proxyForwardList.value().split(",");
    }
    //This method will try to get login IP of user even if servlet is behind reverseProxy or loadBalancer
    public InetAddress getClientAddress(final HttpServletRequest request) throws UnknownHostException {
        String ip = null;
        InetAddress pretender = InetAddress.getByName(request.getRemoteAddr());
        if(doUseForwardHeaders()) {
            if (NetUtils.isIpInCidrList(pretender, proxyNets())) {
                for (String header : getClientAddressHeaders()) {
                    header = header.trim();
                    ip = getCorrectIPAddress(request.getHeader(header));
                    if (StringUtils.isNotBlank(ip)) {
                        LOGGER.debug(String.format("found ip %s in header %s ", ip, header));
                        break;
                    }
                } // no address found in header so ip is blank and use remote addr
            } // else not an allowed proxy address, ip is blank and use remote addr
        }
        if (StringUtils.isBlank(ip)) {
            LOGGER.trace(String.format("no ip found in headers, returning remote address %s.", pretender.getHostAddress()));
            return pretender;
        }

        return InetAddress.getByName(ip);
    }

    private String[] getClientAddressHeaders() {
        return ApiServer.listOfForwardHeaders.value().split(",");
    }

    private static String getCorrectIPAddress(String ip) {
        if(ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            return null;
        }
        if(NetUtils.isValidIp4(ip) || NetUtils.isValidIp6(ip)) {
            return ip;
        }
        //it could be possible to have multiple IPs in HTTP header, this happens if there are multiple proxy in between
        //the client and the servlet, so parse the client IP
        String[] ips = ip.split(",");
        for(String i : ips) {
            if(NetUtils.isValidIp4(i.trim()) || NetUtils.isValidIp6(i.trim())) {
                return i.trim();
            }
        }
        return null;
    }

    private String getCleanParamsString(Map<String, String[]> reqParams) {
        if (MapUtils.isEmpty(reqParams)) {
            return "";
        }

        StringBuilder cleanParamsString = new StringBuilder();
        for (Map.Entry<String, String[]> reqParam : reqParams.entrySet()) {
            if (org.apache.commons.lang3.StringUtils.isBlank(reqParam.getKey())) {
                continue;
            }

            cleanParamsString.append(reqParam.getKey());
            cleanParamsString.append("=");

            if (reqParam.getKey().toLowerCase().contains("password")
                    || reqParam.getKey().toLowerCase().contains("privatekey")
                    || reqParam.getKey().toLowerCase().contains("accesskey")
                    || reqParam.getKey().toLowerCase().contains("secretkey")) {
                cleanParamsString.append("\n");
                continue;
            }

            if (reqParam.getValue() == null || reqParam.getValue().length == 0) {
                cleanParamsString.append("\n");
                continue;
            }

            for (String param : reqParam.getValue()) {
                if (org.apache.commons.lang3.StringUtils.isBlank(param)) {
                    continue;
                }
                String cleanParamString = StringUtils.cleanString(param.trim());
                cleanParamsString.append(cleanParamString);
                cleanParamsString.append(" ");
            }
            cleanParamsString.append("\n");
        }

        return cleanParamsString.toString();
    }
}
