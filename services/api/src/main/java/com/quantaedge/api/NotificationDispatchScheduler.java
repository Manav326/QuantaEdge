package com.quantaedge.api;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Claims due campaigns from PostgreSQL; the scheduler is restart-safe and uses persisted state. */
@Component
@EnableScheduling
public class NotificationDispatchScheduler {
  private static final Logger LOGGER=LoggerFactory.getLogger(NotificationDispatchScheduler.class);
  private final JdbcTemplate jdbc;
  private final RichNotificationService notifications;

  public NotificationDispatchScheduler(JdbcTemplate jdbc,RichNotificationService notifications) {
    this.jdbc=jdbc;
    this.notifications=notifications;
  }

  @Scheduled(fixedDelay=30000L)
  public void dispatchDueCampaigns() {
    List<Long> due=jdbc.queryForList("""
      select id from notification_campaign
      where status='SCHEDULED' and scheduled_at<=now()
      order by scheduled_at,id limit 20
      """,Long.class);
    for(Long id:due) {
      try {
        notifications.dispatchScheduledCampaign(id);
      } catch(RuntimeException failure) {
        // Keep one malformed/stale campaign from preventing unrelated due notifications.
        jdbc.update("""
          update notification_campaign set status='FAILED',updated_at=now()
          where id=? and status='SCHEDULED'
          """,id);
        jdbc.update("""
          insert into notification_audit(
            campaign_id,institution_id,actor_workspace,actor_staff_id,actor_institution_admin_id,
            action_code,details)
          select id,institution_id,sender_workspace,sender_staff_id,sender_institution_admin_id,
            'SCHEDULE_DISPATCH_FAILED',jsonb_build_object('failureType',?)
          from notification_campaign where id=?
          """,failure.getClass().getSimpleName(),id);
        LOGGER.warn("Scheduled notification campaign {} failed with {}",id,failure.getClass().getSimpleName());
      }
    }
  }
}
