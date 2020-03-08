/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.aggregations.pca;

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.search.MultiValueMode;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregatorFactories;
import org.elasticsearch.search.aggregations.AggregatorFactory;
import org.elasticsearch.search.aggregations.support.ArrayValuesSourceAggregationBuilder;
import org.elasticsearch.search.aggregations.support.CoreValuesSourceType;
import org.elasticsearch.search.aggregations.support.ValueType;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.aggregations.support.ValuesSourceConfig;

import java.io.IOException;
import java.util.Map;

public class PCAAggregationBuilder
        extends ArrayValuesSourceAggregationBuilder.LeafOnly<ValuesSource.Numeric, PCAAggregationBuilder> {
    public static final String NAME = "pca";

    public static final ParseField USE_COVARIANCE_FIELD = new ParseField("use_covariance");


    private MultiValueMode multiValueMode = MultiValueMode.AVG;
    private Boolean useCovariance = Boolean.valueOf(false);

    public PCAAggregationBuilder(String name) {
        super(name, CoreValuesSourceType.NUMERIC, ValueType.NUMERIC);
    }

    public PCAAggregationBuilder(PCAAggregationBuilder clone,
                                 AggregatorFactories.Builder factoriesBuilder, Map<String, Object> metaData) {
        super(clone, factoriesBuilder, metaData);
        this.multiValueMode = clone.multiValueMode;
    }

    @Override
    protected AggregationBuilder shallowCopy(AggregatorFactories.Builder factoriesBuilder, Map<String, Object> metaData) {
        return new PCAAggregationBuilder(this, factoriesBuilder, metaData);
    }

    public PCAAggregationBuilder(StreamInput in) throws IOException {
        super(in, CoreValuesSourceType.NUMERIC, ValueType.NUMERIC);
    }

    @Override
    protected void innerWriteTo(StreamOutput out) {
        // do nothing, no extra state to write to stream
    }

    public PCAAggregationBuilder multiValueMode(MultiValueMode multiValueMode) {
        this.multiValueMode = multiValueMode;
        return this;
    }

    public PCAAggregationBuilder setUseCovariance(Boolean useCovariance) {
        this.useCovariance = useCovariance;
        return this;
    }

    @Override
    protected PCAAggregatorFactory innerBuild(QueryShardContext queryShardContext,
                                              Map<String, ValuesSourceConfig<ValuesSource.Numeric>> configs,
                                              AggregatorFactory parent,
                                              AggregatorFactories.Builder subFactoriesBuilder) throws IOException {
        return new PCAAggregatorFactory(name, configs, multiValueMode, useCovariance, queryShardContext, parent,
            subFactoriesBuilder, metaData);
    }

    @Override
    public XContentBuilder doXContentBody(XContentBuilder builder, ToXContent.Params params) throws IOException {
        builder.field(MULTIVALUE_MODE_FIELD.getPreferredName(), multiValueMode);
        builder.field(USE_COVARIANCE_FIELD.getPreferredName(), useCovariance);
        return builder;
    }

    @Override
    public String getType() {
        return NAME;
    }
}