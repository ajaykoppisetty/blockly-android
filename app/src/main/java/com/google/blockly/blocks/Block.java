/*
 * Copyright  2015 Google Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.blockly.blocks;

import android.graphics.Point;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Base class for a Blockly Block.
 */
public class Block {
    private static final String TAG = "Block";

    private static final int DEFAULT_HUE_COLOUR = 0;

    // These values are immutable once a block is created
    private final String mUuid;
    private final String mName;
    private int mCategory;
    private int mColourHue;
    private Connection mOutputConnection;
    private Connection mNextConnection;
    private Connection mPreviousConnection;
    private ArrayList<Input> mInputList;
    private boolean mInputsInline;

    // These values can be changed after creating the block
    private ArrayList<Block> mChildBlocks;
    private Block mParentBlock;
    private String mTooltip;
    private String mComment;
    private boolean mHasContextMenu;
    private boolean mCanDelete;
    private boolean mCanMove;
    private boolean mCanEdit;
    private boolean mCollapsed;
    private boolean mDisabled;
    private Point mPosition;

    private Block(String uuid, String name, int category, int colourHue, Connection outputConnection,
                  Connection nextConnection, Connection previousConnection,
                  ArrayList<Input> inputList, boolean inputsInline) {
        mUuid = uuid;
        mName = name;
        mCategory = category;
        mColourHue = colourHue;
        mOutputConnection = outputConnection;
        mNextConnection = nextConnection;
        mPreviousConnection = previousConnection;
        mInputList = inputList;
        mInputsInline = inputsInline;
    }

    public List<Block> getChildren() {
        return mChildBlocks;
    }

    public void addChild(int position, Block child) {
        mChildBlocks.add(position, child);
    }

    public void removeChild(int position) {
        mChildBlocks.remove(position);
    }

    public String getName() {
        return mName;
    }

    public List<Input> getInputs() {
        return mInputList;
    }

    /**
     * Generate a Blockly Block from JSON. If the JSON is misformatted a {@link RuntimeException}
     * will be thrown. All inputs and fields for the block will also be generated.
     *
     * @param name The name of the block.
     * @param json The JSON to generate the block from.
     * @return The generated Block.
     */
    public static Block fromJson(String name, JSONObject json) {
        if (TextUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Block name may not be null or empty");
        }
        if (json == null) {
            throw new IllegalArgumentException("json may not be null.");
        }
        Builder bob = new Builder(name);

        if (json.has("output") && json.has("previousStatement")) {
            throw new IllegalArgumentException(
                    "Blocks cannot have both an output and a previous statement");
        }
        int colour = json.optInt("colour", DEFAULT_HUE_COLOUR);
        bob.setColour(colour);

        ArrayList<Input> inputs = new ArrayList<>();
        ArrayList<Field> fields = new ArrayList<>();
        for (int i = 0;; i++) {
            String messageKey = "message" + i;
            String argsKey = "args" + i;
            String lastDummyAlignKey = "lastDummyAlign" + i;
            if (!json.has(messageKey)) {
                break;
            }
            String message = json.optString(messageKey);
            JSONArray args = json.optJSONArray(argsKey);
            if (args == null) {
                // If there's no args for this message use an empty array.
                args = new JSONArray();
            }
            // Split on all argument indices of the form "%N" where N is a number from 1 to
            // the number of args without removing them.
            List<String> tokens = tokenizeMessage(message);
            int indexCount = 0;
            // Indices start at 1, make the array 1 bigger so we don't have to offset things
            boolean[] seenIndices = new boolean[args.length() + 1];

            for (String token : tokens) {
                // Check if this token is an argument index of the form "%N"
                if (token.matches("^%\\d+$")) {
                    int index = Integer.parseInt(token.substring(1));
                    if (index < 1 || index > args.length()) {
                        throw new IllegalArgumentException("Message index " + index
                                + " is out of range.");
                    }
                    if (seenIndices[index]) {
                        throw new IllegalArgumentException(("Message index " + index
                                + " is duplicated"));
                    }
                    seenIndices[index] = true;

                    JSONObject element;
                    try {
                        element = args.getJSONObject(index - 1);
                    } catch (JSONException e) {
                        throw new RuntimeException("Error reading arg %" + index, e);
                    }
                    while (element != null) {
                        String type = element.optString("type");
                        if (TextUtils.isEmpty(type)) {
                            throw new IllegalArgumentException("No type for arg %" + index);
                        }

                        if (Field.isFieldType(type)) {
                            fields.add(Field.fromJSON(element));
                            break;
                        } else if (Input.isInputType(type)) {
                            Input input = Input.fromJSON(element);
                            input.addAll(fields);
                            fields.clear();
                            inputs.add(input);
                            break;
                        } else {
                            // Try getting the fallback block if it exists
                            Log.w(TAG, "Unknown element type: " + type);
                            element = element.optJSONObject("alt");
                        }
                    }
                } else {
                    token = token.replace("%%", "%").trim();
                    if (!TextUtils.isEmpty(token)) {
                        fields.add(new Field.FieldLabel(null, token));
                    }
                }
            }

            // Verify every argument was used
            for (int j = 1; j < seenIndices.length; j++) {
                if (!seenIndices[j]) {
                    throw new IllegalArgumentException("Argument " + j + " was never used.");
                }
            }
            // If there were leftover fields we need to add a dummy input to hold them.
            if (fields.size() != 0) {
                String align = json.optString(lastDummyAlignKey, Input.ALIGN_LEFT);
                Input input = new Input.InputDummy(null, align);
                input.addAll(fields);
                inputs.add(input);
                fields.clear();
            }
        }

        bob.setInputs(inputs);
        return bob.build();
    }

    /**
     * Breaks a block message up into args and text. The returned Strings should all either
     * exactly match "^%\\d+$" if they are an arg or else are just text for a label.
     *
     * @param message The message to tokenize.
     * @return A list of Strings that are either an arg or plain text.
     */
    /*package*/ static List<String> tokenizeMessage(String message) {
        ArrayList<String> result = new ArrayList<>();
        if (TextUtils.isEmpty(message)) {
            return result;
        }
        boolean foundPercent = false;
        int lastSplit = 0;
        int lastPercent = -1;

        for (int i = 0, length = message.length(); i < length; i++) {
            char currChar = message.charAt(i);
            if (currChar == '%') {
                if (i + 1 < length) {
                    char nextChar = message.charAt(i + 1);
                    if (nextChar == '%') {
                        i++;
                        continue;
                    } else if (!Character.isDigit(nextChar)) {
                        throw new IllegalArgumentException(
                                "% must be escaped by another % when not denoting an arg."
                                + " Bad message: " + message);
                    }
                } else {
                    throw new IllegalArgumentException(
                            "Messages may not end in an unescaped %. Bad message: " + message);
                }
                foundPercent = true;
                lastPercent = i;
            } else if (foundPercent) {
                if (Character.isDigit(currChar)) {
                    continue;
                } else if (currChar == ' ') {
                    String potentialText = message.substring(lastSplit, lastPercent).trim();
                    if (!TextUtils.isEmpty(potentialText)) {
                        result.add(potentialText);
                    }
                    result.add(message.substring(lastPercent, i));
                    lastSplit = i;
                    foundPercent = false;
                } else {
                    throw new IllegalArgumentException(
                            "Message args may only contain a % followed by numbers, then a space."
                            + " Bad message:" + message);
                }
            }
        }
        if (lastSplit != message.length() - 1) {
            // We have remaining pieces to split
            if (lastPercent > lastSplit) {
                result.add(message.substring(lastSplit, lastPercent));
                result.add(message.substring(lastPercent, message.length()));
            } else {
                result.add(message.substring(lastSplit, message.length()));
            }
        }

        return result;
    }

    public static class Builder {
        // These values are immutable once a block is created
        private String mUuid;
        private String mName;
        private int mCategory;
        private int mColourHue;
        private Connection mOutputConnection;
        private Connection mNextConnection;
        private Connection mPreviousConnection;
        private ArrayList<Input> mInputs;
        private boolean mInputsInline;

        // These values can be changed after creating the block
        private ArrayList<Block> mChildBlocks;
        private Block mParentBlock;
        private String mTooltip;
        private String mComment;
        private boolean mHasContextMenu;
        private boolean mCanDelete;
        private boolean mCanMove;
        private boolean mCanEdit;
        private boolean mCollapsed;
        private boolean mDisabled;
        private Point mPosition;

        public Builder(String name) {
            this(name, UUID.randomUUID().toString());
        }

        public Builder(String name, String uuid) {
            mName = name;
            mUuid = uuid;
            mInputs = new ArrayList<>();
            mChildBlocks = new ArrayList<>();
            mPosition = new Point(0, 0);
        }

        public Builder(Block block) {
            this(block.mName);
            mColourHue = block.mColourHue;
            mCategory = block.mCategory;


            mOutputConnection = block.mOutputConnection;
            mNextConnection = block.mNextConnection;
            mPreviousConnection = block.mPreviousConnection;
            mInputs.addAll(block.mInputList); // TODO: make copies of the inputs/fields
            mInputsInline = block.mInputsInline;

            // TODO: Reconsider the defaults for these
            mTooltip = block.mTooltip;
            mComment = block.mComment;
            mHasContextMenu = block.mHasContextMenu;
            mCanDelete = block.mCanDelete;
            mCanMove = block.mCanMove;
            mCanEdit = block.mCanEdit;
            mCollapsed = block.mCollapsed;
            mDisabled = block.mDisabled;
            mPosition.x = block.mPosition.x;
            mPosition.y = block.mPosition.y;
        }

        public Builder setName(String name) {
            mName = name;
            return this;
        }

        public Builder setUuid(String uuid) {
            mUuid = uuid;
            return this;
        }

        public Builder setColour(int hsvColor) {
            mColourHue = hsvColor;
            return this;
        }

        public Builder setCategory(int category) {
            mCategory = category;
            return this;
        }

        public Builder setOutput(Connection outputConnection) {
            this.mOutputConnection = outputConnection;
            return this;
        }

        public Builder setNext(Connection nextConnection) {
            this.mNextConnection = nextConnection;
            return this;
        }

        public Builder setPrevious(Connection previousConnection) {
            this.mPreviousConnection = previousConnection;
            return this;
        }

        public Builder addInput(Input input) {
            mInputs.add(input);
            return this;
        }

        public Builder setInputs(ArrayList<Input> inputs) {
            if (inputs == null) {
                throw new IllegalArgumentException("inputs may not be null.");
            }
            Log.d(TAG, "Set " + inputs.size() + " inputs");
            this.mInputs = inputs;
            return this;
        }

        public Builder setInputsInline(boolean inputsInline) {
            this.mInputsInline = inputsInline;
            return this;
        }

        public Builder addChild(Block child) {
            if (child == null) {
                throw new IllegalArgumentException("child may not be null.");
            }
            mChildBlocks.add(child);
            return this;
        }

        public Builder setChildren(ArrayList<Block> childBlocks) {
            if (childBlocks == null) {
                throw new IllegalArgumentException("children may not be null.");
            }
            mChildBlocks = childBlocks;
            return this;
        }

        public Builder setParent(Block parentBlock) {
            mParentBlock = parentBlock;
            return this;
        }

        public Builder setTooltip(String tooltip) {
            mTooltip = tooltip;
            return this;
        }

        public Builder setComment(String comment) {
            mComment = comment;
            return this;
        }

        public Builder setHasContextMenu(boolean hasContextMenu) {
            mHasContextMenu = hasContextMenu;
            return this;
        }

        public Builder setCanDelete(boolean canDelete) {
            mCanDelete = canDelete;
            return this;
        }

        public Builder setCanMove(boolean canMove) {
            mCanMove = canMove;
            return this;
        }

        public Builder setCanEdit(boolean canEdit) {
            mCanEdit = canEdit;
            return this;
        }

        public Builder setCollapsed(boolean collapsed) {
            mCollapsed = collapsed;
            return this;
        }

        public Builder setDisabled(boolean disabled) {
            mDisabled = disabled;
            return this;
        }

        public Builder setPosition(int x, int y) {
            mPosition.x = x;
            mPosition.y = y;
            return this;
        }

        public Block build() {
            Block b = new Block(mUuid, mName, mCategory, mColourHue, mOutputConnection, mNextConnection,
                    mPreviousConnection, mInputs, mInputsInline);
            for (int i = 0; i < mChildBlocks.size(); i++) {
                mChildBlocks.get(i).mParentBlock = b;
            }
            b.mChildBlocks = mChildBlocks;
            b.mParentBlock = mParentBlock;
            b.mTooltip = mTooltip;
            b.mComment = mComment;
            b.mHasContextMenu = mHasContextMenu;
            b.mCanDelete = mCanDelete;
            b.mCanMove = mCanMove;
            b.mCanEdit = mCanEdit;
            b.mCollapsed = mCollapsed;
            b.mDisabled = mDisabled;
            b.mPosition = mPosition;
            return b;
        }

    }

    public class Connection {

    }
}
