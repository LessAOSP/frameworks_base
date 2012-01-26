/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.scenegraph;

import java.lang.Math;
import java.util.ArrayList;

import android.content.res.Resources;
import android.renderscript.*;
import android.renderscript.ProgramFragment.Builder;
import android.util.Log;

/**
 * @hide
 */
public class FragmentShader extends Shader {
    ProgramFragment mProgram;
    ScriptField_FragmentShader_s mField;

    public static class Builder {

        FragmentShader mShader;
        ProgramFragment.Builder mBuilder;

        public Builder(RenderScriptGL rs) {
            mShader = new FragmentShader();
            mBuilder = new ProgramFragment.Builder(rs);
        }

        public Builder setShader(Resources resources, int resourceID) {
            mBuilder.setShader(resources, resourceID);
            return this;
        }

        public Builder setObjectConst(Type type) {
            mShader.mPerObjConstants = type;
            return this;
        }

        public Builder setShaderConst(Type type) {
            mShader.mPerShaderConstants = type;
            return this;
        }

        public Builder addShaderTexture(Program.TextureType texType, String name) {
            mShader.mShaderTextureNames.add(name);
            mShader.mShaderTextureTypes.add(texType);
            return this;
        }

        public Builder addTexture(Program.TextureType texType, String name) {
            mShader.mTextureNames.add(name);
            mShader.mTextureTypes.add(texType);
            return this;
        }

        FragmentShader create() {
            if (mShader.mPerShaderConstants != null) {
                mBuilder.addConstant(mShader.mPerShaderConstants);
            }
            if (mShader.mPerObjConstants != null) {
                mBuilder.addConstant(mShader.mPerObjConstants);
            }
            for (int i = 0; i < mShader.mTextureTypes.size(); i ++) {
                mBuilder.addTexture(mShader.mTextureTypes.get(i));
            }
            for (int i = 0; i < mShader.mShaderTextureTypes.size(); i ++) {
                mBuilder.addTexture(mShader.mShaderTextureTypes.get(i));
            }

            mShader.mProgram = mBuilder.create();
            return mShader;
        }
    }

    FragmentShader() {
    }

    public void updateTextures(RenderScriptGL rs, Resources res) {
        int shaderTextureStart = mTextureTypes.size();
        for (int i = 0; i < mShaderTextureNames.size(); i ++) {
            ShaderParam sp = mSourceParams.get(mShaderTextureNames.get(i));
            if (sp != null && sp instanceof TextureParam) {
                TextureParam p = (TextureParam)sp;
                mProgram.bindTexture(p.getTexture().getRsData(rs, res), shaderTextureStart + i);
            }
        }
    }

    public ScriptField_FragmentShader_s getRSData(RenderScriptGL rs, Resources res) {
        if (mField != null) {
            return mField;
        }

        ScriptField_FragmentShader_s.Item item = new ScriptField_FragmentShader_s.Item();
        item.program = mProgram;

        linkConstants(rs, res);
        if (mPerShaderConstants != null) {
            item.shaderConst = mConstantBuffer;
            item.shaderConstParams = mConstantBufferParams;
            mProgram.bindConstants(item.shaderConst, 0);
        }

        item.objectConstIndex = -1;
        if (mPerObjConstants != null) {
            item.objectConstIndex = mPerShaderConstants != null ? 1 : 0;
        }

        mField = new ScriptField_FragmentShader_s(rs, 1);
        mField.set(item, 0, true);
        return mField;
    }
}
