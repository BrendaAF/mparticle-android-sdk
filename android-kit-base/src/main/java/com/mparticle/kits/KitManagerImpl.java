package com.mparticle.kits;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;

import com.mparticle.DeepLinkError;
import com.mparticle.DeepLinkListener;
import com.mparticle.DeepLinkResult;
import com.mparticle.MPEvent;
import com.mparticle.MParticle;
import com.mparticle.commerce.CommerceEvent;
import com.mparticle.internal.CommerceEventUtil;
import com.mparticle.internal.ConfigManager;
import com.mparticle.internal.KitManager;
import com.mparticle.internal.MPUtility;
import com.mparticle.internal.PushRegistrationHelper;
import com.mparticle.kits.mappings.CustomMapping;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class KitManagerImpl extends KitManager implements DeepLinkListener {
    KitIntegrationFactory mKitIntegrationFactory;
    private static final String RESERVED_KEY_LTV = "$Amount";

    private static final String METHOD_NAME = "$MethodName";
    private static final String LOG_LTV = "LogLTVIncrease";
    ConcurrentHashMap<Integer, KitIntegration> providers = new ConcurrentHashMap<Integer, KitIntegration>(0);

    public KitManagerImpl() {
        mKitIntegrationFactory = new KitIntegrationFactory();
    }

    /**
     * Need this method so that we can override it during unit tests.
     */
    protected KitConfiguration createKitConfiguration(JSONObject configuration) throws JSONException {
        return KitConfiguration.createKitConfiguration(configuration);
    }

    public void setKitFactory(KitIntegrationFactory kitIntegrationFactory) {
        mKitIntegrationFactory = kitIntegrationFactory;
    }

    @Override
    public void updateKits(JSONArray kitConfigs) {
        PushRegistrationHelper.PushRegistration pushRegistration = PushRegistrationHelper.getLatestPushRegistration(getContext());
        HashSet<Integer> activeIds = new HashSet<Integer>();
        int currentId = 0;
        if (kitConfigs != null) {
            for (int i = 0; i < kitConfigs.length(); i++) {
                try {
                    JSONObject current = kitConfigs.getJSONObject(i);
                    currentId = current.getInt(KitConfiguration.KEY_ID);
                    activeIds.add(currentId);
                    if (!providers.containsKey(currentId) && mKitIntegrationFactory.isSupported(currentId)) {
                        KitConfiguration configuration = createKitConfiguration(current);
                        KitIntegration provider = mKitIntegrationFactory.createInstance(this, configuration);
                        providers.put(currentId, provider);
                        if (provider.isDisabled()) {
                            continue;
                        }
                        provider.onKitCreate(configuration.getSettings(), getContext());
                        Intent intent = new Intent(MParticle.ServiceProviders.BROADCAST_ACTIVE + currentId);
                        getContext().sendBroadcast(intent);

                        if (provider instanceof KitIntegration.AttributeListener) {
                            syncUserAttributes((KitIntegration.AttributeListener) provider, provider.getConfiguration());
                            syncUserIdentities((KitIntegration.AttributeListener) provider, provider.getConfiguration());
                        }

                        if (pushRegistration != null && !MPUtility.isEmpty(pushRegistration.instanceId) && provider instanceof KitIntegration.PushListener) {
                            if (((KitIntegration.PushListener) provider).onPushRegistration(pushRegistration.instanceId, pushRegistration.senderId)) {
                                ReportingMessage message = ReportingMessage.fromPushRegistrationMessage(provider);
                                getReportingManager().log(message);
                            }
                        }

                    }
                } catch (JSONException jse) {
                    ConfigManager.log(MParticle.LogLevel.ERROR, "Exception while parsing configuration for id " + currentId + ": " + jse.getMessage());
                } catch (Exception e) {
                    ConfigManager.log(MParticle.LogLevel.ERROR, "Exception while starting kit id " + currentId + ": " + e.getMessage());
                }
            }
        }

        Iterator<Integer> ids = providers.keySet().iterator();
        while (ids.hasNext()) {
            Integer id = ids.next();
            if (!activeIds.contains(id)) {
                KitIntegration integration = providers.get(id);
                if (integration != null) {
                    integration.onKitDestroy();
                }
                ids.remove();
                Intent intent = new Intent(MParticle.ServiceProviders.BROADCAST_DISABLED + id);
                getContext().sendBroadcast(intent);
            }
        }

    }

    @Override
    public String getActiveModuleIds() {
        if (providers.isEmpty()) {
            return "";
        } else {
            Set keys = providers.keySet();
            StringBuilder buffer = new StringBuilder(keys.size() * 3);

            Iterator<Integer> it = keys.iterator();
            while (it.hasNext()) {
                Integer next = it.next();
                if (providers.get(next) != null && !providers.get(next).isDisabled()) {
                    buffer.append(next);
                    if (it.hasNext()) {
                        buffer.append(",");
                    }
                }
            }
            return buffer.toString();
        }
    }

    @Override
    public boolean isKitActive(int serviceProviderId) {
        KitIntegration provider = providers.get(serviceProviderId);
        return provider != null && !provider.isDisabled();
    }

    @Override
    public Object getKitInstance(int kitId) {
        KitIntegration kit = providers.get(kitId);
        return kit == null ? null : kit.getInstance();
    }

    //================================================================================
    // General KitIntegration forwarding
    //================================================================================

    @Override
    public void setLocation(Location location) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (!provider.isDisabled()) {
                    provider.setLocation(location);
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call setLocation for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void logNetworkPerformance(String url, long startTime, String method, long length, long bytesSent, long bytesReceived, String requestString, int responseCode) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (!provider.isDisabled()) {
                    List<ReportingMessage> report = provider.logNetworkPerformance(url, startTime, method, length, bytesSent, bytesReceived, requestString, responseCode);
                    getReportingManager().logAll(report);
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call logNetworkPerformance for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public Uri getSurveyUrl(int serviceId, Map<String, String> userAttributes) {
        KitIntegration provider = providers.get(serviceId);
        if (provider != null){
            return provider.getSurveyUrl(provider.getConfiguration().filterAttributes(provider.getConfiguration().getUserAttributeFilters(), userAttributes));
        } else {
            return null;
        }
    }

    @Override
    public void setOptOut(boolean optOutStatus) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (!provider.isDisabled()) {
                    List<ReportingMessage> messages = provider.setOptOut(optOutStatus);
                    getReportingManager().logAll(messages);
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call setOptOut for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    private static boolean setInstallReferrer(Context context, Intent intent, String className) {
        try {
            Class clazz = Class.forName(className);
            Constructor<BroadcastReceiver> constructor = clazz.getDeclaredConstructor();
            constructor.setAccessible(true);
            BroadcastReceiver receiver = constructor.newInstance();
            receiver.onReceive(context, intent);
            return true;
        }catch (Exception e) {
        }
        return false;
    }

    @Override
    public void setInstallReferrer(Context context, Intent intent) {
        if (setInstallReferrer(context, intent, "com.adjust.sdk.AdjustReferrerReceiver")) {
            ConfigManager.log(MParticle.LogLevel.DEBUG, "Sent referral info to Adjust SDK");
        }
        if (setInstallReferrer(context, intent, "com.kochava.android.tracker.ReferralCapture")) {
            ConfigManager.log(MParticle.LogLevel.DEBUG, "Sent referral info to Kochava SDK");
        }
        if (setInstallReferrer(context, intent, "io.branch.referral.InstallListener")) {
            ConfigManager.log(MParticle.LogLevel.DEBUG, "Sent referral info to Branch SDK");
        }
        if (setInstallReferrer(context, intent, "com.localytics.android.ReferralReceiver")) {
            ConfigManager.log(MParticle.LogLevel.DEBUG, "Sent referral info to Localytics SDK");
        }
        if (setInstallReferrer(context, intent, "com.flurry.android.InstallReceiver")) {
            ConfigManager.log(MParticle.LogLevel.DEBUG, "Sent referral info to Flurry SDK");
        }
        if (setInstallReferrer(context, intent, "com.mparticle.kits.AppsFlyerReceiver")) {
            ConfigManager.log(MParticle.LogLevel.DEBUG, "Sent referral info to Appsflyer SDK");
        }
    }

    @Override
    public Set<Integer> getSupportedKits() {
        return mKitIntegrationFactory.getSupportedKits();
    }

    //================================================================================
    // KitIntegration.CommerceListener forwarding
    //================================================================================

    @Override
    public void logCommerceEvent(CommerceEvent event) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.CommerceListener && !provider.isDisabled()) {
                    CommerceEvent filteredEvent = provider.getConfiguration().filterCommerceEvent(event);
                    if (filteredEvent != null) {
                        if (((KitIntegration.CommerceListener)provider).isCommerceSupported()) {
                            List<CustomMapping.ProjectionResult> projectedEvents = CustomMapping.projectEvents(
                                    filteredEvent,
                                    provider.getConfiguration().getCustomMappingList(),
                                    provider.getConfiguration().getDefaultCommerceCustomMapping()
                            );
                            if (projectedEvents != null && projectedEvents.size() > 0) {
                                ReportingMessage masterMessage = ReportingMessage.fromEvent(provider, filteredEvent);
                                boolean forwarded = false;
                                for (int i = 0; i < projectedEvents.size(); i++) {
                                    CustomMapping.ProjectionResult result = projectedEvents.get(i);
                                    List<ReportingMessage> report = null;
                                    String messageType = null;
                                    if (result.getMPEvent() != null) {
                                        report = ((KitIntegration.EventListener) provider).logEvent(projectedEvents.get(i).getMPEvent());
                                        messageType = ReportingMessage.MessageType.EVENT;
                                    } else {
                                        report =((KitIntegration.CommerceListener) provider).logEvent(projectedEvents.get(i).getCommerceEvent());
                                        messageType = ReportingMessage.MessageType.COMMERCE_EVENT;
                                    }
                                    if (report != null && report.size() > 0) {
                                        forwarded = true;
                                        for (ReportingMessage message : report) {
                                            masterMessage.addProjectionReport(
                                                    new ReportingMessage.ProjectionReport(projectedEvents.get(i).getProjectionId(),
                                                            messageType,
                                                            message.getEventName(),
                                                            message.getEventTypeString())
                                            );
                                        }
                                    }
                                }
                                if (forwarded) {
                                    getReportingManager().log(masterMessage);
                                }
                            } else {
                                List<ReportingMessage> reporting = ((KitIntegration.CommerceListener) provider).logEvent(filteredEvent);
                                if (reporting != null && reporting.size() > 0) {
                                    getReportingManager().log(
                                            ReportingMessage.fromEvent(provider, filteredEvent)
                                    );
                                }
                            }
                        } else {
                            List<MPEvent> events = CommerceEventUtil.expand(filteredEvent);
                            boolean forwarded = false;
                            if (events != null) {
                                for (int i = 0; i < events.size(); i++) {
                                    List<ReportingMessage> reporting = ((KitIntegration.EventListener) provider).logEvent(events.get(i));
                                    forwarded = forwarded || (reporting != null && reporting.size() > 0);
                                }
                            }
                            if (forwarded) {
                                getReportingManager().log(
                                        ReportingMessage.fromEvent(provider, filteredEvent)
                                );
                            }
                        }
                    }
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call logCommerceEvent for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    //================================================================================
    // KitIntegration.PushListener forwarding
    //================================================================================

    @Override
    public boolean onMessageReceived(Context context, Intent intent) {
        for (KitIntegration provider : providers.values()) {
            if (provider instanceof KitIntegration.PushListener) {
                try {
                    if (!provider.isDisabled() && ((KitIntegration.PushListener) provider).willHandleMessage(intent.getExtras().keySet())) {
                        ((KitIntegration.PushListener) provider).onMessageReceived(context, intent);
                        ReportingMessage message = ReportingMessage.fromPushMessage(provider, intent);
                        getReportingManager().log(message);
                        return true;
                    }
                } catch (Exception e) {
                    ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call onMessageReceived for kit: " + provider.getName() + ": " + e.getMessage());
                }
            }
        }
        return false;
    }

    @Override
    public boolean onPushRegistration(String token, String senderId) {
        for (KitIntegration provider : providers.values()) {
            if (provider instanceof KitIntegration.PushListener) {
                try {
                    if (!provider.isDisabled()) {
                        if (((KitIntegration.PushListener) provider).onPushRegistration(token, senderId)) {
                            ReportingMessage message = ReportingMessage.fromPushRegistrationMessage(provider);
                            getReportingManager().log(message);
                        }
                        return true;
                    }
                } catch (Exception e) {
                    ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call onPushRegistration for kit: " + provider.getName() + ": " + e.getMessage());
                }
            }
        }
        return false;
    }

    //================================================================================
    // KitIntegration.AttributeListener forwarding
    //================================================================================

    private void syncUserAttributes(KitIntegration.AttributeListener attributeListener, KitConfiguration configuration) {
        Map<String, String> userAttributes = MParticle.getInstance().getUserAttributes();
        Map<String, String> filteredAttributes = KitConfiguration.filterAttributes(configuration.getUserAttributeFilters(),
                userAttributes);
        for (Map.Entry<String, String> entry : filteredAttributes.entrySet()) {
            attributeListener.setUserAttribute(entry.getKey(), entry.getValue());
        }
    }

    private void syncUserIdentities(KitIntegration.AttributeListener attributeListener, KitConfiguration configuration) {
        Map<MParticle.IdentityType, String> identities = getMpInstance().getUserIdentities();
        if (identities != null) {
            for (Map.Entry<MParticle.IdentityType, String> entry : identities.entrySet()){
                if (configuration.shouldSetIdentity(entry.getKey())) {
                    attributeListener.setUserIdentity(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    @Override
    public void setUserAttribute(String attributeKey, String attributeValue) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.AttributeListener && !provider.isDisabled() &&
                        !KitConfiguration.shouldRemoveAttribute(provider.getConfiguration().getUserAttributeFilters(),
                                attributeKey)) {

                    ((KitIntegration.AttributeListener)provider).setUserAttribute(attributeKey, attributeValue);
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call setUserAttributes for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void removeUserAttribute(String key) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.AttributeListener && !provider.isDisabled()) {
                    ((KitIntegration.AttributeListener)provider).removeUserAttribute(key);
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call removeUserAttribute for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void setUserIdentity(String id, MParticle.IdentityType identityType) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.AttributeListener && !provider.isDisabled() && provider.getConfiguration().shouldSetIdentity(identityType)) {
                    ((KitIntegration.AttributeListener)provider).setUserIdentity(identityType, id);
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call setUserIdentity for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void removeUserIdentity(MParticle.IdentityType identityType) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.AttributeListener && !provider.isDisabled()) {
                    ((KitIntegration.AttributeListener)provider).removeUserIdentity(identityType);
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call removeUserIdentity for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void logout() {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.AttributeListener && !provider.isDisabled()) {
                    List<ReportingMessage> report = ((KitIntegration.AttributeListener)provider).logout();
                    getReportingManager().logAll(report);
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call logout for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    //================================================================================
    // KitIntegration.EventListener forwarding
    //================================================================================

    @Override
    public void logEvent(MPEvent event) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.EventListener && !provider.isDisabled() && provider.getConfiguration().shouldLogEvent(event)) {
                    MPEvent eventCopy = new MPEvent(event);
                    eventCopy.setInfo(
                            provider.getConfiguration().filterEventAttributes(eventCopy)
                    );
                    List<CustomMapping.ProjectionResult> projectedEvents = CustomMapping.projectEvents(
                            eventCopy,
                            provider.getConfiguration().getCustomMappingList(),
                            provider.getConfiguration().getDefaultEventProjection()
                    );
                    List<ReportingMessage> reportingMessages = new LinkedList<ReportingMessage>();
                    if (projectedEvents == null) {
                        List<ReportingMessage> messages = null;
                        if (eventCopy.getInfo() != null
                                && eventCopy.getInfo().containsKey(METHOD_NAME)
                                && eventCopy.getInfo().get(METHOD_NAME).equals(LOG_LTV)){
                            messages = ((KitIntegration.CommerceListener) provider).logLtvIncrease(
                                    MParticle.getInstance().Commerce().getCurrentUserLtv(),
                                    new BigDecimal(eventCopy.getInfo().get(RESERVED_KEY_LTV)),
                                    eventCopy.getEventName(),
                                    eventCopy.getInfo());
                        }else {
                            messages = ((KitIntegration.EventListener) provider).logEvent(eventCopy);
                        }
                        if (messages != null && messages.size() > 0) {
                            reportingMessages.addAll(messages);
                        }
                    } else {
                        ReportingMessage masterMessage = ReportingMessage.fromEvent(provider, eventCopy);
                        boolean forwarded = false;
                        for (int i = 0; i < projectedEvents.size(); i++) {
                            List<ReportingMessage> messages = ((KitIntegration.EventListener) provider).logEvent(projectedEvents.get(i).getMPEvent());
                            if (messages != null && messages.size() > 0) {
                                forwarded = true;
                                for (ReportingMessage message : messages) {
                                    ReportingMessage.ProjectionReport report = new ReportingMessage.ProjectionReport(
                                            projectedEvents.get(i).getProjectionId(),
                                            ReportingMessage.MessageType.EVENT,
                                            message.getEventName(),
                                            message.getEventTypeString()
                                    );
                                    masterMessage.addProjectionReport(report);
                                }

                            }
                        }
                        if (forwarded) {
                            reportingMessages.add(masterMessage);
                        }
                    }
                    getReportingManager().logAll(reportingMessages);
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call logEvent for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void leaveBreadcrumb(String breadcrumb) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.EventListener && !provider.isDisabled()) {
                    List<ReportingMessage> report = ((KitIntegration.EventListener)provider).leaveBreadcrumb(breadcrumb);
                    getReportingManager().logAll(report);
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call leaveBreadcrumb for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void logError(String message, Map<String, String> eventData) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.EventListener && !provider.isDisabled()) {
                    List<ReportingMessage> report = ((KitIntegration.EventListener)provider).logError(message, eventData);
                    getReportingManager().logAll(report);
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call logError for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void logException(Exception exception, Map<String, String> eventData, String message) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.EventListener && !provider.isDisabled()) {
                    List<ReportingMessage> report = ((KitIntegration.EventListener)provider).logException(exception, eventData, message);
                    getReportingManager().logAll(report);
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call logException for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void logScreen(String screenName, Map<String, String> eventAttributes) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.EventListener && !provider.isDisabled() && provider.getConfiguration().shouldLogScreen(screenName)) {
                    MPEvent syntheticScreenEvent = new MPEvent.Builder(screenName, MParticle.EventType.Navigation)
                            .info(provider.getConfiguration().filterScreenAttributes(null, screenName, eventAttributes))
                            .build();

                    List<CustomMapping.ProjectionResult> projectedEvents = CustomMapping.projectEvents(
                            syntheticScreenEvent,
                            true,
                            provider.getConfiguration().getCustomMappingList(),
                            provider.getConfiguration().getDefaultEventProjection(),
                            provider.getConfiguration().getDefaultScreenCustomMapping());
                    if (projectedEvents == null) {
                        List<ReportingMessage> report = ((KitIntegration.EventListener) provider).logScreen(screenName, syntheticScreenEvent.getInfo());
                        if (report != null && report.size() > 0) {
                            for (ReportingMessage message : report) {
                                message.setMessageType(ReportingMessage.MessageType.SCREEN_VIEW);
                                message.setScreenName(screenName);
                            }
                        }
                        getReportingManager().logAll(report);
                    } else {
                        ReportingMessage masterMessage = new ReportingMessage(provider,
                                ReportingMessage.MessageType.SCREEN_VIEW,
                                System.currentTimeMillis(),
                                syntheticScreenEvent.getInfo());
                        boolean forwarded = false;
                        for (int i = 0; i < projectedEvents.size(); i++) {
                            List<ReportingMessage> report = ((KitIntegration.EventListener) provider).logEvent(projectedEvents.get(i).getMPEvent());
                            if (report != null && report.size() > 0) {
                                forwarded = true;
                                for (ReportingMessage message : report) {
                                    ReportingMessage.ProjectionReport projectionReport = new ReportingMessage.ProjectionReport(
                                            projectedEvents.get(i).getProjectionId(),
                                            ReportingMessage.MessageType.EVENT,
                                            message.getEventName(),
                                            message.getEventTypeString()
                                    );
                                    masterMessage.addProjectionReport(projectionReport);
                                }
                            }
                        }
                        if (forwarded) {
                            getReportingManager().log(masterMessage);
                        }
                    }
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call logScreen for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    //================================================================================
    // KitIntegration.ActivityListener forwarding
    //================================================================================

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.ActivityListener && !provider.isDisabled()) {
                    List<ReportingMessage> reportingMessages = ((KitIntegration.ActivityListener)provider).onActivityCreated(activity, savedInstanceState);
                    getReportingManager().logAll(reportingMessages);
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call onActivityCreated for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onActivityStarted(Activity activity) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.ActivityListener && !provider.isDisabled()) {
                    List<ReportingMessage> reportingMessages = ((KitIntegration.ActivityListener)provider).onActivityStarted(activity);
                    getReportingManager().logAll(reportingMessages);
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call onActivityStarted for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.ActivityListener && !provider.isDisabled()) {
                    List<ReportingMessage> reportingMessages = ((KitIntegration.ActivityListener)provider).onActivityResumed(activity);
                    getReportingManager().logAll(reportingMessages);
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call onActivityResumed for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.ActivityListener && !provider.isDisabled()) {
                    List<ReportingMessage> reportingMessages = ((KitIntegration.ActivityListener)provider).onActivityPaused(activity);
                    getReportingManager().logAll(reportingMessages);
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call onResume for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onActivityStopped(Activity activity) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.ActivityListener && !provider.isDisabled()) {
                    List<ReportingMessage> reportingMessages = ((KitIntegration.ActivityListener)provider).onActivityStopped(activity);
                    getReportingManager().logAll(reportingMessages);
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call onActivityStopped for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.ActivityListener && !provider.isDisabled()) {
                    List<ReportingMessage> reportingMessages = ((KitIntegration.ActivityListener)provider).onActivitySaveInstanceState(activity, outState);
                    getReportingManager().logAll(reportingMessages);
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call onActivitySaveInstanceState for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        for (KitIntegration provider : providers.values()) {
            try {
                if (provider instanceof KitIntegration.ActivityListener && !provider.isDisabled()) {
                    List<ReportingMessage> reportingMessages = ((KitIntegration.ActivityListener)provider).onActivityDestroyed(activity);
                    getReportingManager().logAll(reportingMessages);
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call onActivityDestroyed for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    //================================================================================
    // DeepLinkListener forwarding
    //================================================================================

    @Override
    public void checkForDeepLink() {
        for (KitIntegration provider : providers.values()) {
            try {
                if (!provider.isDisabled()) {
                    provider.checkForDeepLink();
                }
            } catch (Exception e) {
                ConfigManager.log(MParticle.LogLevel.WARNING, "Failed to call checkForDeeplink for kit: " + provider.getName() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void onResult(DeepLinkResult result) {
        DeepLinkListener listener = MParticle.getInstance().getDeepLinkListener();
        if (listener != null && result != null) {
            ConfigManager.log(MParticle.LogLevel.DEBUG, "Deep link result returned: \n" + result.toString());
            listener.onResult(result);
        }
    }

    @Override
    public void onError(DeepLinkError error) {
        DeepLinkListener listener = MParticle.getInstance().getDeepLinkListener();
        if (listener != null && error != null) {
            ConfigManager.log(MParticle.LogLevel.DEBUG, "Deep link error returned: \n" + error.toString());
            listener.onError(error);
        }
    }

}