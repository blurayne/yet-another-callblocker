package net.evolution515.callblocker.data.numbers;

import android.database.sqlite.SQLiteDatabase;
import android.util.SparseArray;
import android.util.SparseIntArray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.evolution515.callblocker.data.DbCompileService;

/**
 * Reads a source that hands over a YABL file into the table being built.
 *
 * <p>What is in it is the same as in the SQLite databases the same pipeline publishes -
 * numbers with their four counts and a category, a table of what the categories are called,
 * numbers to take out, business names, and meta values - so it goes into the table the same
 * way: the categories matched by name, deletions taken out, the filter asked of every
 * number and name. {@link YablReader} does the reading, one compressed block at a time.
 *
 * <p>The deletions go first. They are numbers an earlier version of the database had and
 * this one doesn't, which is a statement about what is underneath - not about the numbers
 * this file itself brings, which would otherwise be taken out again by their own file.
 */
public class YablImporter {

    private static final Logger LOG = LoggerFactory.getLogger(YablImporter.class);

    private YablImporter() {
    }

    /**
     * @param target   where it is going, open and inside a transaction
     * @param asLayer  whether what is there already has a say, or this writes into an empty table
     * @param filter   which numbers are worth keeping at all, or null for all of them
     * @param progress told after every block how many rows have been read
     */
    static SqliteImporter.Result read(File file, SQLiteDatabase target, NumbersWriter writer,
                                      int sourceId, boolean asLayer, NumbersFilter filter,
                                      SqliteImporter.Progress progress) throws IOException {
        try (YablReader reader = new YablReader(file)) {
            YablReader.Header header = reader.getHeader();

            // the prelude first: the category names are needed before the first row
            SparseArray<String> theirs = new SparseArray<>();
            long[] deletions = {0};

            reader.readPrelude(new YablReader.SimpleVisitor() {
                @Override
                public void onCategory(int id, String name) {
                    theirs.put(id, name);
                }

                @Override
                public void onDeleted(long number) {
                    writer.delete(number);
                    deletions[0]++;
                }
            });

            List<String> added = new ArrayList<>();
            int[] named = {0};

            SparseIntArray categories = SqliteImporter.mapCategories(theirs, target, named,
                    added);

            long[] counts = {0, 0, 0}; // read, skipped, names

            reader.readBlocks(new YablReader.SimpleVisitor() {
                @Override
                public void onBlock(int index, int total, long rowsSoFar) {
                    // once a block: often enough for a cancel, and for the log to move
                    DbCompileService.checkCancelled();

                    if (progress != null && index > 0) progress.onRead(rowsSoFar);
                }

                @Override
                public void onNumber(long number, int positive, int negative, int neutral,
                                     int unknown, int category) {
                    counts[0]++;

                    if (filter != null && !filter.keep(number)) {
                        counts[1]++;
                        return;
                    }

                    int mapped = categories.get(category, category);

                    // the table has seven bits for a category; beyond that it has none
                    if (mapped < 0 || mapped > 127) mapped = 0;

                    int rating = NumberFlags.ratingOf(positive, negative, neutral);
                    int score = NumberFlags.scoreOf(positive, negative);

                    if (asLayer) {
                        writer.merge(number, rating, mapped, score, false, sourceId);
                    } else {
                        writer.put(number, NumberFlags.of(rating, mapped, 0), score, sourceId);
                    }
                }
            });

            reader.readFeatured(new YablReader.SimpleVisitor() {
                @Override
                public void onName(long number, String name) {
                    if (filter != null && !filter.keep(number)) return;
                    if (name == null || name.trim().isEmpty()) return;

                    writer.putName(number, name, sourceId);
                    counts[2]++;
                }
            });

            LOG.info("read() {}: {} rows, {} filtered out, {} deletions, {} names, version {}",
                    file.getName(), counts[0], counts[1], deletions[0], counts[2],
                    header.dbVersion);

            int version = header.dbVersion > 0 && header.dbVersion <= Integer.MAX_VALUE
                    ? (int) header.dbVersion : 0;

            return new SqliteImporter.Result(counts[0] + deletions[0], deletions[0], counts[1],
                    counts[2], named[0], added, version);
        }
    }

}
