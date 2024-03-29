/*
 * Copyright Camunda Services GmbH
 *
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING, OR DISTRIBUTING THE SOFTWARE (“USE”), YOU INDICATE YOUR ACCEPTANCE TO AND ARE ENTERING INTO A CONTRACT WITH, THE LICENSOR ON THE TERMS SET OUT IN THIS AGREEMENT. IF YOU DO NOT AGREE TO THESE TERMS, YOU MUST NOT USE THE SOFTWARE. IF YOU ARE RECEIVING THE SOFTWARE ON BEHALF OF A LEGAL ENTITY, YOU REPRESENT AND WARRANT THAT YOU HAVE THE ACTUAL AUTHORITY TO AGREE TO THE TERMS AND CONDITIONS OF THIS AGREEMENT ON BEHALF OF SUCH ENTITY.
 * “Licensee” means you, an individual, or the entity on whose behalf you receive the Software.
 *
 * Permission is hereby granted, free of charge, to the Licensee obtaining a copy of this Software and associated documentation files to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject in each case to the following conditions:
 * Condition 1: If the Licensee distributes the Software or any derivative works of the Software, the Licensee must attach this Agreement.
 * Condition 2: Without limiting other conditions in this Agreement, the grant of rights is solely for non-production use as defined below.
 * "Non-production use" means any use of the Software that is not directly related to creating products, services, or systems that generate revenue or other direct or indirect economic benefits.  Examples of permitted non-production use include personal use, educational use, research, and development. Examples of prohibited production use include, without limitation, use for commercial, for-profit, or publicly accessible systems or use for commercial or revenue-generating purposes.
 *
 * If the Licensee is in breach of the Conditions, this Agreement, including the rights granted under it, will automatically terminate with immediate effect.
 *
 * SUBJECT AS SET OUT BELOW, THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 * NOTHING IN THIS AGREEMENT EXCLUDES OR RESTRICTS A PARTY’S LIABILITY FOR (A) DEATH OR PERSONAL INJURY CAUSED BY THAT PARTY’S NEGLIGENCE, (B) FRAUD, OR (C) ANY OTHER LIABILITY TO THE EXTENT THAT IT CANNOT BE LAWFULLY EXCLUDED OR RESTRICTED.
 */
package io.camunda.operate.webapp.security.identity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.camunda.identity.sdk.Identity;
import io.camunda.identity.sdk.authentication.AccessToken;
import io.camunda.identity.sdk.authentication.Tokens;
import io.camunda.identity.sdk.authentication.UserDetails;
import io.camunda.identity.sdk.authentication.exception.TokenDecodeException;
import io.camunda.identity.sdk.impl.rest.exception.RestException;
import io.camunda.operate.property.OperateProperties;
import io.camunda.operate.util.SpringContextHolder;
import io.camunda.operate.webapp.security.Permission;
import io.camunda.operate.webapp.security.SessionRepository;
import io.camunda.operate.webapp.security.tenant.OperateTenant;
import io.camunda.operate.webapp.security.tenant.TenantAwareAuthentication;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class IdentityAuthentication extends AbstractAuthenticationToken
    implements Serializable, TenantAwareAuthentication {

  @Serial private static final long serialVersionUID = 1L;

  private static final Logger LOGGER = LoggerFactory.getLogger(IdentityAuthentication.class);

  private Tokens tokens;
  private String id;
  private String name;
  private List<String> permissions;
  @JsonIgnore private List<IdentityAuthorization> authorizations;
  private String subject;
  private Date expires;

  private Date refreshTokenExpiresAt;

  private List<OperateTenant> tenants;

  public IdentityAuthentication() {
    super(null);
  }

  @Override
  public String getCredentials() {
    return tokens.getAccessToken();
  }

  @Override
  public Object getPrincipal() {
    return subject;
  }

  public Tokens getTokens() {
    return tokens;
  }

  private boolean hasExpired() {
    return expires == null || expires.before(new Date());
  }

  private boolean hasRefreshTokenExpired() {
    try {
      LOGGER.info("Refresh token will expire at {}", refreshTokenExpiresAt);
      return refreshTokenExpiresAt == null || refreshTokenExpiresAt.before(new Date());
    } catch (final TokenDecodeException e) {
      LOGGER.info(
          "Refresh token is not a JWT and expire date can not be determined. Error message: {}",
          e.getMessage());
      return false;
    }
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public boolean isAuthenticated() {
    if (hasExpired()) {
      LOGGER.info("Access token is expired");
      if (hasRefreshTokenExpired()) {
        LOGGER.info("No refresh token available. Authentication is invalid.");
        setAuthenticated(false);
        getIdentity().authentication().revokeToken(tokens.getRefreshToken());
        return false;
      } else {
        LOGGER.info("Get a new access token by using refresh token");
        try {
          renewAccessToken();
        } catch (final Exception e) {
          LOGGER.error("Renewing access token failed with exception", e);
          setAuthenticated(false);
        }
      }
    }
    return super.isAuthenticated();
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    final IdentityAuthentication that = (IdentityAuthentication) o;
    return Objects.equals(tokens, that.tokens)
        && Objects.equals(id, that.id)
        && Objects.equals(name, that.name)
        && Objects.equals(permissions, that.permissions)
        && Objects.equals(authorizations, that.authorizations)
        && Objects.equals(subject, that.subject)
        && Objects.equals(expires, that.expires)
        && Objects.equals(refreshTokenExpiresAt, that.refreshTokenExpiresAt)
        && Objects.equals(tenants, that.tenants);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        super.hashCode(),
        tokens,
        id,
        name,
        permissions,
        authorizations,
        subject,
        expires,
        refreshTokenExpiresAt,
        tenants);
  }

  public String getId() {
    return id;
  }

  public List<Permission> getPermissions() {
    final PermissionConverter permissionConverter = getPermissionConverter();
    return permissions.stream().map(permissionConverter::convert).toList();
  }

  public IdentityAuthentication setPermissions(final List<String> permissions) {
    this.permissions = permissions;
    return this;
  }

  public List<IdentityAuthorization> getAuthorizations() {
    if (authorizations == null) {
      synchronized (this) {
        if (authorizations == null) {
          retrieveResourcePermissions();
        }
      }
    }
    return authorizations;
  }

  @Override
  public List<OperateTenant> getTenants() {
    if (tenants == null) {
      synchronized (this) {
        if (tenants == null) {
          retrieveTenants();
        }
      }
    }
    return tenants;
  }

  private void retrieveResourcePermissions() {
    if (getOperateProperties().getIdentity().isResourcePermissionsEnabled()) {
      try {
        authorizations =
            IdentityAuthorization.createFrom(
                getIdentity().authorizations().forToken(tokens.getAccessToken()));
      } catch (final RestException ex) {
        LOGGER.warn(
            "Unable to retrieve resource base permissions from Identity. Error: " + ex.getMessage(),
            ex);
        authorizations = new ArrayList<>();
      }
    }
  }

  private void retrieveTenants() {
    if (getOperateProperties().getMultiTenancy().isEnabled()) {
      try {
        final var accessToken = tokens.getAccessToken();
        final var identityTenants = getIdentity().tenants().forToken(accessToken);

        if (identityTenants != null) {
          tenants =
              identityTenants.stream()
                  .map(t -> new OperateTenant(t.getTenantId(), t.getName()))
                  .toList();
        } else {
          tenants = new ArrayList<>();
        }

      } catch (final RestException ex) {
        LOGGER.warn("Unable to retrieve tenants from Identity. Error: " + ex.getMessage(), ex);
        tenants = new ArrayList<>();
      }
    }
  }

  public void authenticate(final Tokens tokens) {
    if (tokens != null) {
      this.tokens = tokens;
    }
    final AccessToken accessToken =
        getIdentity().authentication().verifyToken(this.tokens.getAccessToken());
    final UserDetails userDetails = accessToken.getUserDetails();
    id = userDetails.getId();
    retrieveName(userDetails);
    permissions = accessToken.getPermissions();
    retrieveResourcePermissions();
    if (!getPermissions().contains(Permission.READ)) {
      throw new InsufficientAuthenticationException("No read permissions");
    }

    retrieveTenants();

    subject = accessToken.getToken().getSubject();
    expires = accessToken.getToken().getExpiresAt();
    LOGGER.info("Access token will expire at {}", expires);
    if (!isPolling()) {
      try {
        refreshTokenExpiresAt =
            getIdentity().authentication().decodeJWT(this.tokens.getRefreshToken()).getExpiresAt();
      } catch (final TokenDecodeException e) {
        LOGGER.error(
            "Unable to decode refresh token {} with exception: {}",
            this.tokens.getRefreshToken(),
            e.getMessage());
      }
    }
    setAuthenticated(!hasExpired());
  }

  private boolean isPolling() {
    final ServletRequestAttributes requestAttributes =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (requestAttributes != null) {
      return Boolean.TRUE.equals(
          Boolean.parseBoolean(
              requestAttributes.getRequest().getHeader(SessionRepository.POLLING_HEADER)));
    } else {
      return false;
    }
  }

  private void retrieveName(final UserDetails userDetails) {
    // Fallback is username e.g 'homer' otherwise empty string.
    final String username = userDetails.getUsername().orElse("");
    // Get display name like 'Homer Simpson' otherwise username e.g. 'homer'
    name = userDetails.getName().orElse(username);
  }

  private void renewAccessToken() throws Exception {
    authenticate(renewTokens(tokens.getRefreshToken()));
  }

  private Tokens renewTokens(final String refreshToken) throws Exception {
    return getIdentityRetryService()
        .requestWithRetry(
            () -> getIdentity().authentication().renewToken(refreshToken),
            "IdentityAuthentication#renewTokens");
  }

  public IdentityAuthentication setExpires(final Date expires) {
    this.expires = expires;
    return this;
  }

  private Identity getIdentity() {
    return SpringContextHolder.getBean(Identity.class);
  }

  private OperateProperties getOperateProperties() {
    return SpringContextHolder.getBean(OperateProperties.class);
  }

  private IdentityRetryService getIdentityRetryService() {
    return SpringContextHolder.getBean(IdentityRetryService.class);
  }

  private PermissionConverter getPermissionConverter() {
    return SpringContextHolder.getBean(PermissionConverter.class);
  }
}
