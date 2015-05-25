/*
 * Copyright 2014-2015 Quantiply Corporation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.quantiply.avro;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;

public class Join {
    private GenericRecordBuilder builder;
    private Schema schema;

    public Join(Schema schema) {
        this.schema = schema;
        this.builder = new GenericRecordBuilder(schema);
    }

    public GenericRecordBuilder getBuilder() {
        return builder;
    }

    public Join merge(GenericRecord record) {
        for (Schema.Field field : record.getSchema().getFields()) {
            Schema.Field outField = schema.getField(field.name());
            if (outField != null) {
                builder.set(outField, record.get(field.pos()));
            }
        }
        return this;
    }
}