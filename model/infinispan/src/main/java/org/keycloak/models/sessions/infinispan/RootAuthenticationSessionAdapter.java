/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.models.sessions.infinispan;

import org.jboss.logging.Logger;
import org.keycloak.common.util.Base64Url;
import org.keycloak.common.util.SecretGenerator;
import org.keycloak.common.util.Time;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.sessions.infinispan.entities.AuthenticationSessionEntity;
import org.keycloak.models.sessions.infinispan.entities.RootAuthenticationSessionEntity;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class RootAuthenticationSessionAdapter implements RootAuthenticationSessionModel {

    private static final Logger log = Logger.getLogger(RootAuthenticationSessionAdapter.class);

    private final KeycloakSession session;
    private final RealmModel realm;
    private final int authSessionsLimit;
    private final SessionEntityUpdater<RootAuthenticationSessionEntity> updater;
    private final static Comparator<Map.Entry<String, AuthenticationSessionEntity>> TIMESTAMP_COMPARATOR =
            Comparator.comparingInt(e -> e.getValue().getTimestamp());

    public RootAuthenticationSessionAdapter(KeycloakSession session, SessionEntityUpdater<RootAuthenticationSessionEntity> updater, RealmModel realm,
                                            int authSessionsLimit) {
        this.session = session;
        this.updater = updater;
        this.realm = realm;
        this.authSessionsLimit = authSessionsLimit;
    }

    void update() {
        updater.onEntityUpdated();
    }


    @Override
    public String getId() {
        return updater.getEntity().getId();
    }

    @Override
    public RealmModel getRealm() {
        return realm;
    }

    @Override
    public int getTimestamp() {
        return updater.getEntity().getTimestamp();
    }

    @Override
    public void setTimestamp(int timestamp) {
        updater.getEntity().setTimestamp(timestamp);
        update();
    }

    @Override
    public Map<String, AuthenticationSessionModel> getAuthenticationSessions() {
        Map<String, AuthenticationSessionModel> result = new HashMap<>();

        for (Map.Entry<String, AuthenticationSessionEntity> entry : updater.getEntity().getAuthenticationSessions().entrySet()) {
            String tabId = entry.getKey();
            result.put(tabId , new AuthenticationSessionAdapter(session, this, tabId, entry.getValue()));
        }

        return result;
    }

    @Override
    public AuthenticationSessionModel getAuthenticationSession(ClientModel client, String tabId) {
        if (client == null || tabId == null) {
            return null;
        }

        AuthenticationSessionModel authSession = getAuthenticationSessions().get(tabId);
        if (authSession != null && client.equals(authSession.getClient())) {
            session.getContext().setAuthenticationSession(authSession);
            return authSession;
        } else {
            return null;
        }
    }

    @Override
    public AuthenticationSessionModel createAuthenticationSession(ClientModel client) {
        Objects.requireNonNull(client, "client");

        Map<String, AuthenticationSessionEntity> authenticationSessions = updater.getEntity().getAuthenticationSessions();
        if (authenticationSessions.size() >= authSessionsLimit) {
            String tabId = authenticationSessions.entrySet().stream().min(TIMESTAMP_COMPARATOR).map(Map.Entry::getKey).orElse(null);

            if (tabId != null) {
                log.debugf("Reached limit (%s) of active authentication sessions per a root authentication session. Removing oldest authentication session with TabId %s.", authSessionsLimit, tabId);

                // remove the oldest authentication session
                authenticationSessions.remove(tabId);
            }
        }

        AuthenticationSessionEntity authSessionEntity = new AuthenticationSessionEntity();
        authSessionEntity.setClientUUID(client.getId());

        int timestamp = Time.currentTime();
        authSessionEntity.setTimestamp(timestamp);

        String tabId = Base64Url.encode(SecretGenerator.getInstance().randomBytes(8));
        authenticationSessions.put(tabId, authSessionEntity);

        // Update our timestamp when adding new authenticationSession
        updater.getEntity().setTimestamp(timestamp);

        update();

        AuthenticationSessionAdapter authSession = new AuthenticationSessionAdapter(session, this, tabId, authSessionEntity);
        session.getContext().setAuthenticationSession(authSession);
        return authSession;
    }

    @Override
    public void removeAuthenticationSessionByTabId(String tabId) {
        if (updater.getEntity().getAuthenticationSessions().remove(tabId) != null) {
            if (updater.getEntity().getAuthenticationSessions().isEmpty()) {
                updater.onEntityRemoved();
            } else {
                updater.getEntity().setTimestamp(Time.currentTime());
                update();
            }
        }
    }

    @Override
    public void restartSession(RealmModel realm) {
        updater.getEntity().getAuthenticationSessions().clear();
        updater.getEntity().setTimestamp(Time.currentTime());
        update();
    }
}
