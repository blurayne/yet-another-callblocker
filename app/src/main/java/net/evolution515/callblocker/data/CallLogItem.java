package net.evolution515.callblocker.data;

import android.provider.CallLog;

public class CallLogItem {

    public enum Type {
        INCOMING, OUTGOING, MISSED, REJECTED, BLOCKED, OTHER;

        public static Type fromProviderType(int type) {
            switch (type) {
                case CallLog.Calls.INCOMING_TYPE: return INCOMING;
                case CallLog.Calls.OUTGOING_TYPE: return OUTGOING;
                case CallLog.Calls.MISSED_TYPE:
                case CallLog.Calls.VOICEMAIL_TYPE:
                    return MISSED;
                case CallLog.Calls.REJECTED_TYPE: return REJECTED;
                // the system logs a call the screening service disallowed as blocked
                case CallLog.Calls.BLOCKED_TYPE: return BLOCKED;
                default: return OTHER;
            }
        }
    }

    /** How the number reached the phone: withheld numbers have no number to show. */
    public enum Presentation {
        ALLOWED, RESTRICTED, UNKNOWN, PAYPHONE;

        public static Presentation fromProviderValue(int value) {
            switch (value) {
                case CallLog.Calls.PRESENTATION_RESTRICTED:
                    return RESTRICTED;
                case CallLog.Calls.PRESENTATION_UNKNOWN:
                    return UNKNOWN;
                case CallLog.Calls.PRESENTATION_PAYPHONE:
                    return PAYPHONE;
                default:
                    return ALLOWED;
            }
        }

        public boolean hasNumber() {
            return this == ALLOWED;
        }
    }

    public long id;
    public Type type;
    public String number;
    public Presentation presentation;
    public long timestamp;
    public long duration;
    public NumberInfo numberInfo;

    /** What the app did about the call when it came in, or null if it didn't see it. */
    public CallDecisionLog.Decision decision;

    public CallLogItem(long id, Type type, String number, Presentation presentation,
                       long timestamp, long duration) {
        this.id = id;
        this.type = type;
        this.number = number;
        this.presentation = presentation;
        this.timestamp = timestamp;
        this.duration = duration;
    }
}
