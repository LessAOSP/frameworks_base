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

package android.databinding.tool.writer

import android.databinding.tool.LayoutBinder

class DataBinderWriter(val pkg: String, val projectPackage: String, val className: String,
        val layoutBinders : List<LayoutBinder>, val minSdk : kotlin.Int) {
    fun write() =
            kcode("") {
                nl("package $pkg;")
                nl("import $projectPackage.BR;")
                nl("class $className {") {
                    tab("public android.databinding.ViewDataBinding getDataBinder(android.view.View view, int layoutId) {") {
                        tab("switch(layoutId) {") {
                            layoutBinders.groupBy{it.getLayoutname()}.forEach {
                                tab("case ${it.value.first!!.getModulePackage()}.R.layout.${it.value.first!!.getLayoutname()}:") {
                                    if (it.value.size() == 1) {
                                        tab("return ${it.value.first!!.getPackage()}.${it.value.first!!.getImplementationName()}.bind(view);")
                                    } else {
                                        // we should check the tag to decide which layout we need to inflate
                                        tab("{") {
                                            tab("final Object tag = view.getTag();")
                                            tab("if(tag == null) throw new java.lang.RuntimeException(\"view must have a tag\");")
                                            it.value.forEach {
                                                tab("if (\"${it.getTag()}\".equals(tag)) {") {
                                                    tab("return new ${it.getPackage()}.${it.getImplementationName()}(view);")
                                                } tab("}")
                                            }
                                        }tab("}")
                                    }

                                }
                            }
                        }
                        tab("}")
                        tab("return null;")
                    }
                    tab("}")

                    tab("public int getId(String key) {") {
                        tab("return BR.getId(key);")
                    } tab("}")

                    tab("final static int TARGET_MIN_SDK = ${minSdk};")
                }
                nl("}")
            }.generate()
}