package dev.yzlaboratory.alexandrea.auth.mail;

import java.time.Duration;

final class DurationCopy {

    private DurationCopy() {}

    static String hours(Duration validFor) {
        var hours = validFor.toHours();
        return hours + (hours == 1 ? " hour" : " hours");
    }
}
