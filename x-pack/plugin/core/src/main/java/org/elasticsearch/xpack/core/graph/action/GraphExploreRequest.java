/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.core.graph.action;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.ValidateActions;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.aggregations.bucket.sampler.SamplerAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.significant.SignificantTerms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Holds the criteria required to guide the exploration of connected terms which
 * can be returned as a graph.
 */
public class GraphExploreRequest extends ActionRequest implements IndicesRequest.Replaceable {

    public static final String NO_HOPS_ERROR_MESSAGE = "Graph explore request must have at least one hop";
    public static final String NO_VERTICES_ERROR_MESSAGE = "Graph explore hop must have at least one VertexRequest";
    private String[] indices = Strings.EMPTY_ARRAY;
    private IndicesOptions indicesOptions = IndicesOptions.fromOptions(false, false, true, false);
    private String[] types = Strings.EMPTY_ARRAY;
    private String routing;
    private TimeValue timeout;

    private int sampleSize = SamplerAggregationBuilder.DEFAULT_SHARD_SAMPLE_SIZE;
    private String sampleDiversityField;
    private int maxDocsPerDiversityValue;
    private boolean useSignificance = true;
    private boolean returnDetailedInfo;

    private List<Hop> hops = new ArrayList<>();

    public GraphExploreRequest() {
    }

    /**
     * Constructs a new graph request to run against the provided
     * indices. No indices means it will run against all indices.
     */
    public GraphExploreRequest(String... indices) {
        this.indices = indices;
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (hops.size() == 0) {
            validationException = ValidateActions.addValidationError(NO_HOPS_ERROR_MESSAGE, validationException);
        }
        for (Hop hop : hops) {
            validationException = hop.validate(validationException);
        }
        return validationException;
    }

    @Override
    public String[] indices() {
        return this.indices;
    }

    
    @Override
    public GraphExploreRequest indices(String... indices) {
        this.indices = indices;
        return this;
    }

    @Override
    public IndicesOptions indicesOptions() {
        return indicesOptions;
    }

    public GraphExploreRequest indicesOptions(IndicesOptions indicesOptions) {
        if (indicesOptions == null) {
            throw new IllegalArgumentException("IndicesOptions must not be null");
        }
        this.indicesOptions = indicesOptions;
        return this;
    }

    public String[] types() {
        return this.types;
    }

    public GraphExploreRequest types(String... types) {
        this.types = types;
        return this;
    }

    public String routing() {
        return this.routing;
    }

    public GraphExploreRequest routing(String routing) {
        this.routing = routing;
        return this;
    }

    public GraphExploreRequest routing(String... routings) {
        this.routing = Strings.arrayToCommaDelimitedString(routings);
        return this;
    }

    public TimeValue timeout() {
        return timeout;
    }

    /**
     * Graph exploration can be set to timeout after the given period. Search operations involved in 
     * each hop are limited to the remaining time available but can still overrun due to the nature
     * of their "best efforts" timeout support. When a timeout occurs partial results are returned.
     * @param timeout a {@link TimeValue} object which determines the maximum length of time to spend exploring
     */
    public GraphExploreRequest timeout(TimeValue timeout) {
        if (timeout == null) {
            throw new IllegalArgumentException("timeout must not be null");
        }
        this.timeout = timeout;
        return this;
    }

    public GraphExploreRequest timeout(String timeout) {
        timeout(TimeValue.parseTimeValue(timeout, null, getClass().getSimpleName() + ".timeout"));
        return this;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);

        indices = in.readStringArray();
        indicesOptions = IndicesOptions.readIndicesOptions(in);
        types = in.readStringArray();
        routing = in.readOptionalString();
        timeout = in.readOptionalTimeValue();
        sampleSize = in.readInt();
        sampleDiversityField = in.readOptionalString();
        maxDocsPerDiversityValue = in.readInt();
        
        useSignificance = in.readBoolean();
        returnDetailedInfo = in.readBoolean();
        
        int numHops = in.readInt();
        Hop parentHop = null;
        for (int i = 0; i < numHops; i++) {
            Hop hop = new Hop(parentHop);
            hop.readFrom(in);
            hops.add(hop);
            parentHop = hop;
        }

    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeStringArray(indices);
        indicesOptions.writeIndicesOptions(out);
        out.writeStringArray(types);
        out.writeOptionalString(routing);
        out.writeOptionalTimeValue(timeout);

        out.writeInt(sampleSize);
        out.writeOptionalString(sampleDiversityField);
        out.writeInt(maxDocsPerDiversityValue);
        
        out.writeBoolean(useSignificance);
        out.writeBoolean(returnDetailedInfo);
        out.writeInt(hops.size());
        for (Iterator<Hop> iterator = hops.iterator(); iterator.hasNext();) {
            Hop hop = iterator.next();
            hop.writeTo(out);
        }
    }

    @Override
    public String toString() {
        return "graph explore [" + Arrays.toString(indices) + "][" + Arrays.toString(types) + "]";
    }

    /**
     * The number of top-matching documents that are considered during each hop (default is 
     * {@link SamplerAggregationBuilder#DEFAULT_SHARD_SAMPLE_SIZE}
     * Very small values (less than 50) may not provide sufficient weight-of-evidence to identify
     * significant connections between terms. 
     * <p> Very large values (many thousands) are not recommended with loosely defined queries (fuzzy queries or those 
     *  with many OR clauses).
     *  This is because any useful signals in the best documents are diluted with irrelevant noise from low-quality matches.
     *  Performance is also typically better with smaller samples as there are less look-ups required for background frequencies 
     *  of terms found in the documents  
     * </p>
     * 
     * @param maxNumberOfDocsPerHop shard-level sample size in documents
     */
    public void sampleSize(int maxNumberOfDocsPerHop) {
        sampleSize = maxNumberOfDocsPerHop;
    }

    public int sampleSize() {
        return sampleSize;
    }

    /**
     * Optional choice of single-value field on which to diversify sampled
     * search results
     */
    public void sampleDiversityField(String name) {
        sampleDiversityField = name;
    }

    public String sampleDiversityField() {
        return sampleDiversityField;
    }

    /**
     * Optional number of permitted docs with same value in sampled search
     * results. Must also declare which field using sampleDiversityField
     */
    public void maxDocsPerDiversityValue(int maxDocs) {
        this.maxDocsPerDiversityValue = maxDocs;
    }

    public int maxDocsPerDiversityValue() {
        return maxDocsPerDiversityValue;
    }

    /**
     * Controls the choice of algorithm used to select interesting terms. The default
     * value is true which means terms are selected based on significance (see the {@link SignificantTerms}
     * aggregation) rather than popularity (using the {@link TermsAggregator}).  
     * @param value true if the significant_terms algorithm should be used.
     */
    public void useSignificance(boolean value) {
        this.useSignificance = value;
    }

    public boolean useSignificance() {
        return useSignificance;
    }
    
    /**
     * Return detailed information about vertex frequencies as part of JSON results - defaults to false
     * @param value true if detailed information is required in JSON responses
     */
    public void returnDetailedInfo(boolean value) {
        this.returnDetailedInfo = value;
    }    

    public boolean returnDetailedInfo() {
        return returnDetailedInfo;
    }
    

    /**
     * Add a stage in the graph exploration. Each hop represents a stage of 
     * querying elasticsearch to identify terms which can then be connnected
     * to other terms in a subsequent hop.
     * @param guidingQuery optional choice of query which influences which documents
     * are considered in this stage
     * @return a {@link Hop} object that holds settings for a stage in the graph exploration
     */
    public Hop createNextHop(QueryBuilder guidingQuery) {
        Hop parent = null;
        if (hops.size() > 0) {
           parent = hops.get(hops.size() - 1);
        }
        Hop newHop = new Hop(parent);
        newHop.guidingQuery = guidingQuery;
        hops.add(newHop);
        return newHop;
    }

    public int getHopNumbers() {
        return hops.size();
    }

    public Hop getHop(int hopNumber) {
        return hops.get(hopNumber);
    }

    public static class TermBoost {
        String term;
        float boost;

        public TermBoost(String term, float boost) {
            super();
            this.term = term;
            if (boost <= 0) {
                throw new IllegalArgumentException("Boosts must be a positive non-zero number");
            }
            this.boost = boost;
        }

        TermBoost() {
        }

        public String getTerm() {
            return term;
        }

        public float getBoost() {
            return boost;
        }

        void readFrom(StreamInput in) throws IOException {
            this.term = in.readString();
            this.boost = in.readFloat();
        }

        void writeTo(StreamOutput out) throws IOException {
            out.writeString(term);
            out.writeFloat(boost);
        }

    }
    

}
