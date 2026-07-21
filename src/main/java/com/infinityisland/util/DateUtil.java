package com.infinityisland.util;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Date helpers with PST (America/Los_Angeles) day boundaries to match the client. */
public final class DateUtil {
  private static final ZoneId PST = ZoneId.of("America/Los_Angeles");
  private static final DateTimeFormatter ISO_LOCAL_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

  private DateUtil() {
  }

  /**
   * Today's date in PST, formatted yyyy-MM-dd (e.g., 2025-11-04).
   */
  public static String today() {
    return ZonedDateTime.now(PST).format(ISO_LOCAL_DATE);
  }

  public static String yesterday() { return LocalDate.now(PST).minusDays(1).format(ISO_LOCAL_DATE); }

}