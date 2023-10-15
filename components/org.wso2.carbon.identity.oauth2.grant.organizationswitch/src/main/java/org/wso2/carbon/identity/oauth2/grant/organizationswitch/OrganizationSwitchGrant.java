/*
 * Copyright (c) 2022-2023, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.oauth2.grant.organizationswitch;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2ClientException;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2ServerException;
import org.wso2.carbon.identity.oauth2.dto.OAuth2AccessTokenRespDTO;
import org.wso2.carbon.identity.oauth2.dto.OAuth2ClientApplicationDTO;
import org.wso2.carbon.identity.oauth2.dto.OAuth2TokenValidationRequestDTO;
import org.wso2.carbon.identity.oauth2.dto.OAuth2TokenValidationResponseDTO;
import org.wso2.carbon.identity.oauth2.grant.organizationswitch.exception.OrganizationSwitchGrantException;
import org.wso2.carbon.identity.oauth2.grant.organizationswitch.internal.OrganizationSwitchGrantDataHolder;
import org.wso2.carbon.identity.oauth2.grant.organizationswitch.util.OrganizationSwitchGrantConstants;
import org.wso2.carbon.identity.oauth2.grant.organizationswitch.util.OrganizationSwitchGrantUtil;
import org.wso2.carbon.identity.oauth2.model.AccessTokenDO;
import org.wso2.carbon.identity.oauth2.model.RequestParameter;
import org.wso2.carbon.identity.oauth2.token.OAuthTokenReqMessageContext;
import org.wso2.carbon.identity.oauth2.token.bindings.TokenBinding;
import org.wso2.carbon.identity.oauth2.token.handlers.grant.AbstractAuthorizationGrantHandler;
import org.wso2.carbon.identity.oauth2.util.OAuth2Util;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;

import java.util.Arrays;

import static java.util.Objects.nonNull;

import static org.wso2.carbon.identity.organization.management.service.constant.OrganizationManagementConstants.ErrorMessages.ERROR_CODE_ORGANIZATION_NOT_FOUND_FOR_TENANT;

/**
 * Implements the AuthorizationGrantHandler for the OrganizationSwitch grant type.
 */
public class OrganizationSwitchGrant extends AbstractAuthorizationGrantHandler {

    private static final Log LOG = LogFactory.getLog(OrganizationSwitchGrant.class);
    private static final String TOKEN_BINDING_REFERENCE = "tokenBindingReference";

    @Override
    public boolean validateGrant(OAuthTokenReqMessageContext tokReqMsgCtx) throws IdentityOAuth2Exception {

        super.validateGrant(tokReqMsgCtx);

        String token = extractParameter(OrganizationSwitchGrantConstants.Params.TOKEN_PARAM, tokReqMsgCtx);
        String switchOrganizationId = extractParameter(OrganizationSwitchGrantConstants.Params.ORG_PARAM, tokReqMsgCtx);
        OAuth2TokenValidationResponseDTO validationResponseDTO = validateToken(token);

        if (!validationResponseDTO.isValid()) {
            LOG.debug("Access token validation failed.");

            throw new IdentityOAuth2Exception("Invalid token received.");
        }

        LOG.debug("Access token validation success.");

        AccessTokenDO tokenDO = OAuth2Util.findAccessToken(token, false);
        AuthenticatedUser authorizedUser = nonNull(tokenDO) ? tokenDO.getAuthzUser() :
                AuthenticatedUser.createLocalAuthenticatedUserFromSubjectIdentifier(
                        validationResponseDTO.getAuthorizedUser());

        String appResideOrganization = getOrganizationIdFromTenantDomain(authorizedUser.getTenantDomain());
        checkOrganizationIsAllowedToSwitch(appResideOrganization, switchOrganizationId);

        AuthenticatedUser authenticatedUser = new AuthenticatedUser(authorizedUser);
        authenticatedUser.setAccessingOrganization(switchOrganizationId);
        if (StringUtils.isEmpty(authorizedUser.getUserResidentOrganization())) {
            authenticatedUser.setUserResidentOrganization(appResideOrganization);
        } else {
            authenticatedUser.setUserResidentOrganization(authorizedUser.getUserResidentOrganization());
        }

        tokReqMsgCtx.setAuthorizedUser(authenticatedUser);

        String[] allowedScopes = tokReqMsgCtx.getOauth2AccessTokenReqDTO().getScope();
        tokReqMsgCtx.setScope(allowedScopes);
        if (tokenDO.getTokenBinding() != null) {
            tokReqMsgCtx.addProperty(TOKEN_BINDING_REFERENCE, tokenDO.getTokenBinding());
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Issuing an access token for user: " + authenticatedUser + " with scopes: " +
                    Arrays.toString(tokReqMsgCtx.getScope()));
        }
        return true;
    }

    @Override
    public OAuth2AccessTokenRespDTO issue(OAuthTokenReqMessageContext tokReqMsgCtx) throws IdentityOAuth2Exception {

        if (tokReqMsgCtx.getProperty(TOKEN_BINDING_REFERENCE) != null) {
            tokReqMsgCtx.setTokenBinding((TokenBinding) tokReqMsgCtx.getProperty(TOKEN_BINDING_REFERENCE));
        }
        return super.issue(tokReqMsgCtx);
    }

    private String extractParameter(String param, OAuthTokenReqMessageContext tokReqMsgCtx) {

        RequestParameter[] parameters = tokReqMsgCtx.getOauth2AccessTokenReqDTO().getRequestParameters();

        if (parameters != null) {
            for (RequestParameter parameter : parameters) {
                if (param.equals(parameter.getKey())) {
                    if (ArrayUtils.isNotEmpty(parameter.getValue())) {
                        return parameter.getValue()[0];
                    }
                }
            }
        }

        return null;
    }

    /**
     * Validate access token.
     *
     * @param accessToken
     * @return OAuth2TokenValidationResponseDTO of the validated token
     */
    private OAuth2TokenValidationResponseDTO validateToken(String accessToken) {

        OAuth2TokenValidationRequestDTO requestDTO = new OAuth2TokenValidationRequestDTO();
        OAuth2TokenValidationRequestDTO.OAuth2AccessToken token = requestDTO.new OAuth2AccessToken();

        token.setIdentifier(accessToken);
        token.setTokenType("bearer");
        requestDTO.setAccessToken(token);

        OAuth2TokenValidationRequestDTO.TokenValidationContextParam contextParam = requestDTO.new
                TokenValidationContextParam();

        OAuth2TokenValidationRequestDTO.TokenValidationContextParam[] contextParams = {contextParam};
        requestDTO.setContext(contextParams);

        OAuth2ClientApplicationDTO clientApplicationDTO = OrganizationSwitchGrantDataHolder.getInstance()
                .getOAuth2TokenValidationService().findOAuthConsumerIfTokenIsValid(requestDTO);
        return clientApplicationDTO.getAccessTokenValidationResponse();
    }

    private String getOrganizationIdFromTenantDomain(String tenantDomain) throws OrganizationSwitchGrantException {

        try {
            return OrganizationSwitchGrantDataHolder.getInstance().getOrganizationManager()
                    .resolveOrganizationId(tenantDomain);
        } catch (OrganizationManagementException e) {
            throw OrganizationSwitchGrantUtil.handleServerException(ERROR_CODE_ORGANIZATION_NOT_FOUND_FOR_TENANT, e);
        }
    }

    private void checkOrganizationIsAllowedToSwitch(String currentOrgId, String switchOrgId)
            throws IdentityOAuth2Exception {

        if (StringUtils.equals(currentOrgId, switchOrgId)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Provided token was already issued for the requested organization: " + switchOrgId);
            }
            throw new IdentityOAuth2ClientException(
                    "Provided token was already issued for the requested organization.");
        }
        try {
            if (getOrganizationManager()
                    .getRelativeDepthBetweenOrganizationsInSameBranch(currentOrgId, switchOrgId) < 0) {
                throw new IdentityOAuth2ClientException("Organization switch is only allowed for the organizations " +
                        "in the same branch.");
            }
        } catch (OrganizationManagementException e) {
            throw new IdentityOAuth2ServerException("Error while checking organizations allowed to switch.", e);
        }
    }

    private OrganizationManager getOrganizationManager() {

        return OrganizationSwitchGrantDataHolder.getInstance().getOrganizationManager();
    }
}
