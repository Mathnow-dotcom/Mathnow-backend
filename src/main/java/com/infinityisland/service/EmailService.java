package com.infinityisland.service;

import com.infinityisland.dao.user.User;
import com.infinityisland.dao.VideoRating;
import org.springframework.stereotype.Service;

/**
 * Replace with your real mailer. Throwing an exception simulates a critical failure.
 */
@Service
public class EmailService {
  private volatile boolean forceFail = false;

  public void setForceFail(boolean forceFail) { this.forceFail = forceFail; }

  public void sendRatingReport(User user, VideoRating rating) {
    if (forceFail) {
      throw new RuntimeException("Simulated mail transport failure");
    }
    // TODO: integrate SMTP / provider here
  }
}