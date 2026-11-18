package com.sidescreen.app

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.appcompat.widget.AppCompatEditText

/**
 * Invisible 1x1 EditText proxy that acts as an Android IME target when the
 * user opens the on-screen keyboard during streaming. Disables fullscreen
 * extraction and predictive composing buffers so every keystroke goes straight
 * to the Mac.
 */
class KeyboardInputProxyView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = android.R.attr.editTextStyle,
    ) : AppCompatEditText(context, attrs, defStyleAttr) {
        var onTextCommitted: ((String) -> Unit)? = null
        var onBackspace: ((Int) -> Unit)? = null
        var onKeyEventForwarded: ((KeyEvent) -> Boolean)? = null

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = false
            background = null
            setTextColor(Color.TRANSPARENT)
            setHintTextColor(Color.TRANSPARENT)
            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions =
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_NONE
        }

        override fun onCheckIsTextEditor(): Boolean = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            outAttrs.imeOptions =
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_NONE

            return object : BaseInputConnection(this, false) {
                override fun commitText(
                    text: CharSequence?,
                    newCursorPosition: Int,
                ): Boolean {
                    val str = text?.toString()
                    if (!str.isNullOrEmpty()) {
                        onTextCommitted?.invoke(str)
                    }
                    return true
                }

                override fun setComposingText(
                    text: CharSequence?,
                    newCursorPosition: Int,
                ): Boolean {
                    val str = text?.toString()
                    if (!str.isNullOrEmpty()) {
                        onTextCommitted?.invoke(str)
                    }
                    return true
                }

                override fun deleteSurroundingText(
                    beforeLength: Int,
                    afterLength: Int,
                ): Boolean {
                    if (beforeLength > 0) {
                        onBackspace?.invoke(beforeLength)
                    }
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent?): Boolean {
                    if (event != null && onKeyEventForwarded?.invoke(event) == true) {
                        return true
                    }
                    return super.sendKeyEvent(event)
                }
            }
        }
    }
