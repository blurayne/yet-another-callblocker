package net.evolution515.callblocker.data;

public interface ContactsProvider {

    ContactItem get(String number);

    boolean isInLimitedMode();

}
