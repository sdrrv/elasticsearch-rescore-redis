package com.bigdataboutique.elasticsearch.plugin;

import com.bigdataboutique.elasticsearch.plugin.exceptions.ScoreOperatorException;// Exceptions

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TopDocs;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.fielddata.AtomicFieldData;
import org.elasticsearch.index.fielddata.AtomicNumericFieldData;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.fielddata.plain.SortedSetDVBytesAtomicFieldData;
import org.elasticsearch.index.query.QueryRewriteContext;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.search.rescore.RescoreContext;
import org.elasticsearch.search.rescore.Rescorer;
import org.elasticsearch.search.rescore.RescorerBuilder;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;

import static java.util.Collections.singletonList;
import static org.elasticsearch.common.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.common.xcontent.ConstructingObjectParser.optionalConstructorArg;

public class RedisRescoreBuilder extends RescorerBuilder<RedisRescoreBuilder> {
    public static final String NAME = "redis";

    protected static final Logger log = LogManager.getLogger(RedisRescoreBuilder.class);

    private final String keyField;
    private final String keyPrefix;
    private final String scoreOperator;

    private final String[] possibleOperators = new String[]{"MULTIPLY","ADD","SUBTRACT","SET"};

    private static Jedis jedis;

    public static void setJedis(Jedis j) {
        jedis = j;
    }

    public Boolean checkOperator(String operator){ // checks if its possible to use that operator
        for (String possibleOperator : possibleOperators){
            if (operator.equals(possibleOperator))
                return true;
        }
        return false;
    }


// Constructors--------------------------------------------------------------------------------------------------
    public RedisRescoreBuilder(final String keyField, @Nullable String keyPrefix, String scoreOperator) throws ScoreOperatorException {
        this.keyField = keyField;
        this.keyPrefix = keyPrefix;
        this.scoreOperator = scoreOperator;
        if (!checkOperator(scoreOperator))
            throw new ScoreOperatorException(scoreOperator, "Wrong type operator:");
    }

    public RedisRescoreBuilder(StreamInput in) throws IOException {
        super(in);
        keyField = in.readString();
        keyPrefix = in.readOptionalString();
        scoreOperator = in.readOptionalString();
        if (!checkOperator(scoreOperator))
            throw new ScoreOperatorException(scoreOperator, "Wrong type operator:");
    }
//--------------------------------------------------------------------------------------------------

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeString(keyField);
        out.writeOptionalString(keyPrefix);
        out.writeOptionalString(scoreOperator);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public RescorerBuilder<RedisRescoreBuilder> rewrite(QueryRewriteContext ctx) throws IOException {
        return this;
    }

    private static final ParseField KEY_FIELD = new ParseField("key_field");
    private static final ParseField KEY_PREFIX = new ParseField("key_prefix");
    private static final ParseField SCORE_OPERATOR = new ParseField("score_operator");

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.field(KEY_FIELD.getPreferredName(), keyField);
        if (keyPrefix != null)
            builder.field(KEY_PREFIX.getPreferredName(), keyPrefix);

        if (scoreOperator != null)
            builder.field(SCORE_OPERATOR.getPreferredName(), scoreOperator);

    }

    private static final ConstructingObjectParser<RedisRescoreBuilder, Void> PARSER =
            new ConstructingObjectParser<RedisRescoreBuilder, Void>(NAME,
            args -> {
                try {
                    if (args.length == 3)
                        return new RedisRescoreBuilder((String) args[0], (String) args[1], (String) args[2]);
                    else
                        return new RedisRescoreBuilder((String) args[0], (String) args[1], "MULTIPLY");
                } catch (ScoreOperatorException e) {
                    throw new IllegalArgumentException(e);
                }
            }
            );
    static {
        PARSER.declareString(constructorArg(), KEY_FIELD);
        PARSER.declareString(optionalConstructorArg(), KEY_PREFIX);
        PARSER.declareString(optionalConstructorArg(), SCORE_OPERATOR);
    }
    public static RedisRescoreBuilder fromXContent(XContentParser parser) {
        return PARSER.apply(parser, null);
    }

    @Override
    public RescoreContext innerBuildContext(int windowSize, QueryShardContext context) throws IOException {
        IndexFieldData<?> keyField =
                this.keyField == null ? null : context.getForField(context.fieldMapper(this.keyField));
        return new RedisRescoreContext(windowSize, keyPrefix, keyField, scoreOperator);
    }

    @Override
    public boolean equals(Object obj) {
        if (!super.equals(obj)) {
            return false;
        }
        RedisRescoreBuilder other = (RedisRescoreBuilder) obj;
        return keyField.equals(other.keyField)
                && Objects.equals(keyPrefix, other.keyPrefix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), keyField, keyPrefix);
    }

    String keyField() {
        return keyField;
    }

    @Nullable
    String keyPrefix() {
        return keyPrefix;
    }

    String scoreOperator() {
        return scoreOperator;
    }

    private static class RedisRescoreContext extends RescoreContext {
        private final String keyPrefix;
        private final String scoreOperator;
        @Nullable
        private final IndexFieldData<?> keyField;

        RedisRescoreContext(int windowSize, String keyPrefix, @Nullable IndexFieldData<?> keyField, String scoreOperator) {
            super(windowSize, RedisRescorer.INSTANCE);
            this.keyPrefix = keyPrefix;
            this.keyField = keyField;
            this.scoreOperator = scoreOperator;
        }
    }

    private static class RedisRescorer implements Rescorer {

        private static final RedisRescorer INSTANCE = new RedisRescorer();



        private static String getTermFromFieldData(int topLevelDocId, AtomicFieldData fd,
                LeafReaderContext leaf, String fieldName) throws IOException {
            String term = null;
            if (fd instanceof SortedSetDVBytesAtomicFieldData) {
                final SortedSetDocValues data = ((SortedSetDVBytesAtomicFieldData) fd).getOrdinalsValues();
                if (data != null) {
                    if (data.advanceExact(topLevelDocId - leaf.docBase)) {
                        // document does have data for the field
                        term = data.lookupOrd(data.nextOrd()).utf8ToString();
                    }
                }
            } else if (fd instanceof AtomicNumericFieldData) {
                final SortedNumericDocValues data = ((AtomicNumericFieldData) fd).getLongValues();
                if (data != null) {
                    if (!data.advanceExact(topLevelDocId - leaf.docBase)) {
                        throw new IllegalArgumentException("document [" + topLevelDocId
                                + "] does not have the field [" + fieldName + "]");
                    }
                    if (data.docValueCount() > 1) {
                        throw new IllegalArgumentException("document [" + topLevelDocId
                                + "] has more than one value for [" + fieldName + "]");
                    }
                    term = String.valueOf(data.nextValue());
                }
            }
            return term;
        }

        @Override
        public TopDocs rescore(TopDocs topDocs, IndexSearcher searcher, RescoreContext rescoreContext) throws IOException {
            assert rescoreContext != null;
            if (topDocs == null || topDocs.scoreDocs.length == 0) {
                return topDocs;
            }

            final RedisRescoreContext context = (RedisRescoreContext) rescoreContext;



            if (context.keyField != null) {
                /*
                 * Since this example looks up a single field value it should
                 * access them in docId order because that is the order in
                 * which they are stored on disk and we want reads to be
                 * forwards and close together if possible.
                 *
                 * If accessing multiple fields we'd be better off accessing
                 * them in (reader, field, docId) order because that is the
                 * order they are on disk.
                 */

                final Iterator<LeafReaderContext> leaves = searcher.getIndexReader().leaves().iterator();
                LeafReaderContext leaf = null;

                final int end = Math.min(topDocs.scoreDocs.length, rescoreContext.getWindowSize());
                SortedSetDocValues docValues = null;
                SortedNumericDocValues numericDocValues = null;
                int endDoc = 0;
                for (int i = 0; i < end; i++) {
                    if (topDocs.scoreDocs[i].doc >= endDoc) {
                        do {
                            leaf = leaves.next();
                            endDoc = leaf.docBase + leaf.reader().maxDoc();
                        } while (topDocs.scoreDocs[i].doc >= endDoc);

                        final AtomicFieldData fd = context.keyField.load(leaf);
                        if (fd instanceof SortedSetDVBytesAtomicFieldData) {
                            docValues = ((SortedSetDVBytesAtomicFieldData) fd).getOrdinalsValues();
                        } else if (fd instanceof AtomicNumericFieldData) {
                            numericDocValues = ((AtomicNumericFieldData) fd).getLongValues();
                        }
                    }

                    if (docValues != null) {
                        if (docValues.advanceExact(topDocs.scoreDocs[i].doc - leaf.docBase)) {
                            // document does have data for the field
                            final String term = docValues.lookupOrd(docValues.nextOrd()).utf8ToString();

                            switch (context.scoreOperator) {
                                case "ADD":
                                    topDocs.scoreDocs[i].score += getScoreFactor(term, context.keyPrefix);
                                    break;
                                case "MULTIPLY":
                                    topDocs.scoreDocs[i].score *= getScoreFactor(term, context.keyPrefix);
                                    break;
                                case "SUBTRACT":
                                    topDocs.scoreDocs[i].score -= getScoreFactor(term, context.keyPrefix);
                                    break;
                                case "SET":
                                    topDocs.scoreDocs[i].score = getScoreFactor(term, context.keyPrefix);
                                    break;

                            }
                            //gympass
                        }

                    } else if (numericDocValues != null) {
                        if (!numericDocValues.advanceExact(topDocs.scoreDocs[i].doc - leaf.docBase)) {
                            throw new IllegalArgumentException("document [" + topDocs.scoreDocs[i].doc
                                    + "] does not have the field [" + context.keyField.getFieldName() + "]");
                        }
                        if (numericDocValues.docValueCount() > 1) {
                            throw new IllegalArgumentException("document [" + topDocs.scoreDocs[i].doc
                                    + "] has more than one value for [" + context.keyField.getFieldName() + "]");
                        }

                        switch (context.scoreOperator) {
                            case "ADD":
                                topDocs.scoreDocs[i].score += getScoreFactor(String.valueOf(numericDocValues.nextValue()),
                                        context.keyPrefix);
                                break;
                            case "MULTIPLY":
                                topDocs.scoreDocs[i].score *= getScoreFactor(String.valueOf(numericDocValues.nextValue()),
                                        context.keyPrefix);
                                break;
                            case "SUBTRACT":
                                topDocs.scoreDocs[i].score -= getScoreFactor(String.valueOf(numericDocValues.nextValue()),
                                        context.keyPrefix);
                                break;
                            case "SET":
                                topDocs.scoreDocs[i].score = getScoreFactor(String.valueOf(numericDocValues.nextValue()),
                                        context.keyPrefix);
                                break;
                        }

                    }
                }
            }

            // Sort by score descending, then docID ascending, just like lucene's QueryRescorer
            Arrays.sort(topDocs.scoreDocs, (a, b) -> {
                if (a.score > b.score) {
                    return -1;
                }
                if (a.score < b.score) {
                    return 1;
                }
                // Safe because doc ids >= 0
                return a.doc - b.doc;
            });
            return topDocs;
        }

        private static float getScoreFactor(final String key, @Nullable final String keyPrefix) {
            assert key != null;

            return AccessController.doPrivileged((PrivilegedAction<Float>) () -> {
                final String fullKey = fullKey(key, keyPrefix);
                final String factor = jedis.get(fullKey);
                if (factor == null) {
                    log.debug("Redis rescore factor null for key " + keyPrefix + key);
                    return 1.0f;
                }

                try {
                    return Float.parseFloat(factor);
                } catch (NumberFormatException ignored_e) {
                    log.warn("Redis rescore factor NumberFormatException for key " + fullKey);
                    return 1.0f;
                }
            });
        }

        private static String fullKey(final String key, @Nullable final String keyPrefix) {
            if (keyPrefix == null) {
                return key;
            } else {
                return keyPrefix + key;
            }
        }

        @Override
        public Explanation explain(int topLevelDocId, IndexSearcher searcher, RescoreContext rescoreContext,
                                   Explanation sourceExplanation) throws IOException {
            final RedisRescoreContext context = (RedisRescoreContext) rescoreContext;
            final Iterator<LeafReaderContext> leaves = searcher.getIndexReader().leaves().iterator();
            LeafReaderContext leaf = null;
            int endDoc = 0;
            do {
                leaf = leaves.next();
                endDoc = leaf.docBase + leaf.reader().maxDoc();
            } while (topLevelDocId >= endDoc);

            AtomicFieldData fd = context.keyField.load(leaf);
            String fieldName = context.keyField.getFieldName();
            String term = getTermFromFieldData(topLevelDocId, fd, leaf, fieldName);
            if (term != null) {
                float score = getScoreFactor(term, context.keyPrefix);
                return Explanation.match(score, fieldName, singletonList(sourceExplanation));
            } else {
                return Explanation.noMatch(fieldName, sourceExplanation);
            }
        }
    }
}
