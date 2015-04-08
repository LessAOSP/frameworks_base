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

import android.databinding.testapp.databinding.LayoutWithIncludeBinding;
import android.databinding.testapp.vo.NotBindableVo;

import android.test.UiThreadTest;
import android.widget.TextView;

public class IncludeTagTest extends BaseDataBinderTest<LayoutWithIncludeBinding> {

    public IncludeTagTest() {
        super(LayoutWithIncludeBinding.class);
    }

    @UiThreadTest
    public void testIncludeTag() {
        NotBindableVo vo = new NotBindableVo(3, "a");
        mBinder.setOuterObject(vo);
        mBinder.executePendingBindings();
        final TextView outerText = (TextView) mBinder.getRoot().findViewById(R.id.outerTextView);
        assertEquals("a", outerText.getText());
        final TextView innerText = (TextView) mBinder.getRoot().findViewById(R.id.innerTextView);
        assertEquals("modified 3a", innerText.getText().toString());

        vo.setIntValue(5);
        vo.setStringValue("b");
        mBinder.invalidateAll();
        mBinder.executePendingBindings();
        assertEquals("b", outerText.getText());
        assertEquals("modified 5b", innerText.getText().toString());
    }
}
