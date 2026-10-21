package com.quantaedge.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Delivers queued FCM attempts independently of campaign/in-app persistence. */
@Component
public class NotificationPushDispatchScheduler {
  private static final Logger LOGGER=LoggerFactory.getLogger(NotificationPushDispatchScheduler.class);
  private final AndroidPushService push;

  public NotificationPushDispatchScheduler(AndroidPushService push) {
    this.push=push;
  }

  @Scheduled(fixedDelay=15000L)
  public void dispatchAndroidPush() {
    try {
      push.dispatchPendingDeliveries();
    } catch (RuntimeException failure) {
      // Persisted attempts remain available for the next tick; do not stop campaign scheduling.
      LOGGER.warn("Android push dispatch cycle failed with {}",failure.getClass().getSimpleName());
    }
  }
}
