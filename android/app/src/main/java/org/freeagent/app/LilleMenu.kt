package org.freeagent.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The ONE popup menu surface (v1.0.2, Ethuram's word on the
 * shipped long-press menu: "it's off, should be sleek, not
 * like vibecoded stuff" — the old one rendered reactions as
 * chunky grey boxes and actions as full-width grey bars in
 * caps, each popup pieced together separately).
 *
 * Every popup menu in the app — the message long-press menu,
 * the header ⋯ menu, chat-list row actions — is built from
 * this one component, in the house language (true black,
 * tonal lifted surface, subtle border, orange spent only as
 * the alive accent — a menu is chrome, so no orange here):
 *
 * - ONE compact floating card: tonal gradient surface,
 *   rounded corners, 1 dp border, modest padding; dismisses
 *   on outside tap / back (the dialog's own behaviour).
 * - Reactions: a single neat row of bare emoji glyphs — NO
 *   boxes, NO filled tiles; generous invisible tap targets.
 * - Actions: slim sentence-case text rows with hairline
 *   dividers — no filled bars, no caps.
 *
 * Presentation only: the actions, order and plumbing behind
 * every menu are exactly what they were (MessageActions,
 * MainChatActions, ReplyQuote); LilleMenuSpec (its own pure
 * file) pins the style numbers so the surfaces cannot drift
 * apart again.
 */
object LilleMenu {

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** The floating card itself: tonal lifted surface,
     *  rounded corners, subtle border. */
    fun card(context: Context): LinearLayout {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(context, LilleMenuSpec.CARD_PADDING_DP),
                dp(context, LilleMenuSpec.CARD_PADDING_DP),
                dp(context, LilleMenuSpec.CARD_PADDING_DP),
                dp(context, LilleMenuSpec.CARD_PADDING_DP)
            )
        }
        val bg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.parseColor(LilleMenuSpec.CARD_TOP_HEX),
                Color.parseColor(LilleMenuSpec.CARD_BOTTOM_HEX)
            )
        ).apply {
            cornerRadius = dp(context, LilleMenuSpec.CARD_CORNER_DP).toFloat()
            setStroke(
                dp(context, 1),
                Color.parseColor(LilleMenuSpec.CARD_STROKE_HEX)
            )
        }
        layout.background = bg
        return layout
    }

    /** Show [content] (usually a [card]) as a floating menu:
     *  transparent dialog window so ONLY the card shows,
     *  dismiss on outside tap / back. */
    fun show(context: Context, content: View): AlertDialog {
        val dialog = AlertDialog.Builder(context).setView(content).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        return dialog
    }

    /** One slim action row: sentence-case text, no fill, no
     *  bar — a line of text that happens to be tappable. */
    fun actionRow(
        context: Context,
        title: String,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = title
            textSize = LilleMenuSpec.ROW_TEXT_SP
            setTextColor(Color.parseColor(LilleMenuSpec.TEXT_HEX))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(
                dp(context, LilleMenuSpec.ROW_PADDING_H_DP),
                dp(context, LilleMenuSpec.ROW_PADDING_V_DP),
                dp(context, LilleMenuSpec.ROW_PADDING_H_DP),
                dp(context, LilleMenuSpec.ROW_PADDING_V_DP)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }
    }

    /** A hairline divider between action rows. */
    fun divider(context: Context): View {
        return View(context).apply {
            setBackgroundColor(Color.parseColor(LilleMenuSpec.DIVIDER_HEX))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)
            ).apply {
                leftMargin = dp(context, LilleMenuSpec.ROW_PADDING_H_DP)
                rightMargin = dp(context, LilleMenuSpec.ROW_PADDING_H_DP)
            }
        }
    }

    /** One reaction: a bare glyph with a generous invisible
     *  tap target — no box, no tile, no background. */
    fun reactionCell(
        context: Context,
        emoji: String,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            text = emoji
            textSize = LilleMenuSpec.REACTION_TEXT_SP
            gravity = Gravity.CENTER
            val size = dp(context, LilleMenuSpec.REACTION_CELL_DP)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { onClick() }
        }
    }

    /** A reactions row: [emojis] as bare glyphs, with the
     *  "+" affordance at the end when [onMore] is given. */
    fun reactionRow(
        context: Context,
        emojis: List<String>,
        onPick: (String) -> Unit,
        onMore: (() -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (emoji in emojis) {
            row.addView(reactionCell(context, emoji) { onPick(emoji) })
        }
        if (onMore != null) {
            row.addView(TextView(context).apply {
                text = "+"
                textSize = LilleMenuSpec.REACTION_TEXT_SP
                setTextColor(Color.parseColor(LilleMenuSpec.TEXT_HEX))
                gravity = Gravity.CENTER
                val size = dp(context, LilleMenuSpec.REACTION_CELL_DP)
                layoutParams = LinearLayout.LayoutParams(size, size)
                setOnClickListener { onMore() }
            })
        }
        return row
    }

    /** A simple text menu: [titles] as slim rows with
     *  hairline dividers, in one card; tapping a row
     *  dismisses and reports its index. */
    fun simpleMenu(
        context: Context,
        titles: List<String>,
        onPick: (Int) -> Unit
    ): AlertDialog {
        val card = card(context)
        var dialog: AlertDialog? = null
        titles.forEachIndexed { index, title ->
            if (index > 0) card.addView(divider(context))
            card.addView(actionRow(context, title) {
                dialog?.dismiss()
                onPick(index)
            })
        }
        dialog = show(context, card)
        return dialog
    }
}
