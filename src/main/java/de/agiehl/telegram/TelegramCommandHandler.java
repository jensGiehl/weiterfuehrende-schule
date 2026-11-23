package de.agiehl.telegram;

import de.agiehl.sourcenews.School;

import java.io.IOException;

public interface TelegramCommandHandler {

    long updateOffset();

    void handle(long nextOffset, School school, boolean enabled) throws IOException;

    void status(long nextOffset) throws IOException;

    void advance(long nextOffset) throws IOException;
}
