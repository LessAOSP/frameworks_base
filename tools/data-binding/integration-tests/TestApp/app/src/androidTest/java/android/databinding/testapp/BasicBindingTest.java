/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.databinding.testapp;

import android.databinding.testapp.databinding.BasicBindingBinding;

import android.test.UiThreadTest;

public class BasicBindingTest extends BaseDataBinderTest<BasicBindingBinding> {
    public BasicBindingTest() {
        super(BasicBindingBinding.class);
    }

    @UiThreadTest
    public void testTextViewContentInInitialization() {
        assertAB("X", "Y");
    }

    @UiThreadTest
    public void testNullValuesInInitialization() {
        assertAB(null, null);
    }

    @UiThreadTest
    public void testSecondIsNullInInitialization() {
        assertAB(null, "y");
    }

    @UiThreadTest
    public void testFirstIsNullInInitialization() {
        assertAB("x", null);
    }

    @UiThreadTest
    public void testTextViewContent() {
        assertAB("X", "Y");
    }

    @UiThreadTest
    public void testNullValues() {
        assertAB(null, null);
    }

    @UiThreadTest
    public void testSecondIsNull() {
        assertAB(null, "y");
    }

    @UiThreadTest
    public void testFirstIsNull() {
        assertAB("x", null);
    }

    private void assertAB(String a, String b) {
        mBinder.setA(a);
        mBinder.setB(b);
        rebindAndAssert(a + b);
    }

    private void rebindAndAssert(String text) {
        mBinder.executePendingBindings();
        assertEquals(text, mBinder.textView.getText().toString());
    }
}
