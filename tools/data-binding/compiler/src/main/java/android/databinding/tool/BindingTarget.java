/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.databinding.tool;

import android.databinding.tool.expr.Expr;
import android.databinding.tool.expr.ExprModel;
import android.databinding.tool.reflection.ModelAnalyzer;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.store.ResourceBundle;
import android.databinding.tool.store.SetterStore;

import java.util.ArrayList;
import java.util.List;

public class BindingTarget {
    List<Binding> mBindings = new ArrayList<Binding>();
    ExprModel mModel;
    ModelClass mResolvedClass;
    String mFieldName;

    // if this target presents itself in multiple layout files with different view types,
    // it receives an interface type and should use it in the getter instead.
    private ResourceBundle.BindingTargetBundle mBundle;

    public BindingTarget(ResourceBundle.BindingTargetBundle bundle) {
        mBundle = bundle;
    }

    public boolean isUsed() {
        return mBundle.isUsed();
    }

    public void addBinding(String name, Expr expr) {
        mBindings.add(new Binding(this, name, expr));
    }

    public String getInterfaceType() {
        return mBundle.getInterfaceType() == null ? mBundle.getFullClassName() : mBundle.getInterfaceType();
    }

    public String getId() {
        return mBundle.getId();
    }

    public String getTag() {
        return mBundle.getTag();
    }

    public String getOriginalTag() {
        return mBundle.getOriginalTag();
    }

    public String getViewClass() {
        return mBundle.getFullClassName();
    }

    public ModelClass getResolvedType() {
        if (mResolvedClass == null) {
            mResolvedClass = ModelAnalyzer.getInstance().findClass(mBundle.getFullClassName(),
                    mModel.getImports());
        }
        return mResolvedClass;
    }

    public String getIncludedLayout() {
        return mBundle.getIncludedLayout();
    }

    public boolean isBinder() {
        return getIncludedLayout() != null;
    }

    public boolean supportsTag() {
        return !SetterStore.get(ModelAnalyzer.getInstance())
                .isUntaggable(mBundle.getFullClassName());
    }

    public List<Binding> getBindings() {
        return mBindings;
    }

    public ExprModel getModel() {
        return mModel;
    }

    public void setModel(ExprModel model) {
        mModel = model;
    }

    public void setFieldName(String fieldName) {
        mFieldName = fieldName;
    }

    public String getFieldName() {
        return mFieldName;
    }
}
