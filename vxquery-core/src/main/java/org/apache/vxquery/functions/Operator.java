/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.vxquery.functions;

import javax.xml.namespace.QName;

import org.apache.vxquery.v0runtime.base.FunctionIteratorFactory;

public class Operator extends Function {
    private FunctionIteratorFactory fif;

    public Operator(QName name, Signature signature, FunctionIteratorFactory fif) {
        super(name, signature);
        this.fif = fif;
    }

    @Override
    public FunctionTag getTag() {
        return FunctionTag.OPERATOR;
    }

    @Override
    public boolean useContextImplicitly() {
        return false;
    }

    @Override
    public FunctionIteratorFactory getIteratorFactory() {
        return fif;
    }
}