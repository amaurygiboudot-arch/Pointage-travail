package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2BackupManager
import com.amaury.pointage.v2.V2ManualSessionWriter
import com.amaury.pointage.v2.V2ProfileStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private data class V2ManualDialogColors(
    val background: Int,
    val panel: Int,
    val text: Int,
    val secondary: Int,
    val gold: Int,
    val goldLight: Int
)

/**
 * Owner unique du point d'entrée et du dialogue de saisie manuelle V2.
 *
 * Le bouton est ajouté directement à l'écran de pointage : aucune recherche de vue legacy,
 * aucun slot d'entreprise implicite et aucune écriture dans PointageStore.
 */
object V2ManualEntryInstaller {
    private const val BUTTON_TAG = "v2_manual_entry_button"

    fun install(activity: Activity) {
        if (!HoraTrackV2.ENABLED) return
        val panel = activity.findViewById<LinearLayout>(R.id.pointageButtons) ?: return
        if (panel.findViewWithTag<Button>(BUTTON_TAG) != null) return

        panel.addView(
            Button(activity).apply {
                tag = BUTTON_TAG
                text = "✎  AJOUTER DES HEURES MANUELLEMENT"
                isAllCaps = false
                setBackgroundResource(R.drawable.hp_panel)
                setTextColor(Color.parseColor("#F3A64A"))
                setOnClickListener { showDialog(activity) }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(activity, 10)
                marginEnd = dp(activity, 10)
                topMargin = dp(activity, 8)
            }
        )
    }

    private fun showDialog(activity: Activity) {
        val storedCompanies = SalaryCompanyStore.readConfirmed(activity)
        if (!storedCompanies.reliable) {
            Toast.makeText(
                activity,
                "Saisie manuelle bloquée : vérifie le stockage des entreprises avant d'ajouter une plage.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val colors = dialogColors(activity)
        val selectedDate = Calendar.getInstance(Locale.FRANCE)
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
        val activeCompanyId = V2ProfileStore.activeCompanyId(activity)

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 12))
        }
        body.addView(TextView(activity).apply {
            text = "♛  SAISIE MANUELLE V2"
            gravity = Gravity.CENTER
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colors.goldLight)
        })
        body.addView(TextView(activity).apply {
            text = "Ajoute une plage oubliée. Elle sera enregistrée directement dans l'historique V2."
            textSize = 14f
            setTextColor(colors.secondary)
            setPadding(0, dp(activity, 8), 0, dp(activity, 14))
        })

        val dateButton = Button(activity).apply {
            text = "Date : ${dateFormat.format(selectedDate.time)}"
            isAllCaps = false
            setTextColor(colors.goldLight)
            background = rounded(activity, colors.panel, 16, colors.gold)
        }
        dateButton.setOnClickListener {
            DatePickerDialog(
                activity,
                { _, year, month, day ->
                    selectedDate.set(year, month, day)
                    dateButton.text = "Date : ${dateFormat.format(selectedDate.time)}"
                },
                selectedDate.get(Calendar.YEAR),
                selectedDate.get(Calendar.MONTH),
                selectedDate.get(Calendar.DAY_OF_MONTH)
            ).apply {
                setOnShowListener {
                    getButton(DatePickerDialog.BUTTON_POSITIVE)?.setTextColor(colors.goldLight)
                    getButton(DatePickerDialog.BUTTON_NEGATIVE)?.setTextColor(colors.goldLight)
                }
            }.show()
        }

        val startInput = themedInput(activity, colors, "Heure de début — ex. 05:00").apply {
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        }
        val endInput = themedInput(activity, colors, "Heure de fin — ex. 13:00").apply {
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        }
        val placeInput = themedInput(activity, colors, "Lieu / client (facultatif)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }

        val companyGroup = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        val companyByButtonId = linkedMapOf<Int, String>()
        storedCompanies.companies.forEachIndexed { index, company ->
            val button = companyRadio(
                activity,
                colors,
                buildString {
                    append(company.name.ifBlank { "Entreprise ${index + 1}" })
                    if (company.siret.isNotBlank()) append(" — SIRET ${company.siret}")
                }
            ).apply {
                isChecked = company.id == activeCompanyId ||
                    (activeCompanyId == null && index == 0)
            }
            companyByButtonId[button.id] = company.id
            companyGroup.addView(button)
        }
        val noCompany = companyRadio(activity, colors, "Sans entreprise / autre")
        companyGroup.addView(noCompany)
        if (companyGroup.checkedRadioButtonId == -1) noCompany.isChecked = true

        body.addView(
            dateButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 54))
        )
        body.addView(startInput)
        body.addView(endInput)
        body.addView(TextView(activity).apply {
            text = "Entreprise"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colors.goldLight)
            setPadding(0, dp(activity, 12), 0, dp(activity, 4))
        })
        body.addView(companyGroup)
        body.addView(placeInput)

        val cancel = Button(activity).apply {
            text = "Annuler"
            isAllCaps = false
            setTextColor(colors.secondary)
            background = rounded(activity, colors.panel, 14)
        }
        val add = Button(activity).apply {
            text = "Ajouter"
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#15100A"))
            background = rounded(activity, colors.goldLight, 14)
        }
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 16))
            background = rounded(activity, colors.background, 20)
            addView(cancel, LinearLayout.LayoutParams(0, dp(activity, 50), 1f).apply {
                marginEnd = dp(activity, 6)
            })
            addView(add, LinearLayout.LayoutParams(0, dp(activity, 50), 1f).apply {
                marginStart = dp(activity, 6)
            })
        }
        val scroll = ScrollView(activity).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                body,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(activity, colors.background, 24, colors.gold)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(actions)
        }
        val dialog = AlertDialog.Builder(activity).setView(root).create()
        dialog.setOnShowListener {
            val metrics = activity.resources.displayMetrics
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout((metrics.widthPixels * 0.92f).toInt(), (metrics.heightPixels * 0.86f).toInt())
        }
        cancel.setOnClickListener { dialog.dismiss() }
        add.setOnClickListener {
            val startMs = parseTime(selectedDate, startInput.text.toString())
            val rawEndMs = parseTime(selectedDate, endInput.text.toString())
            if (startMs == null) {
                startInput.error = "Format attendu : HH:mm"
                return@setOnClickListener
            }
            if (rawEndMs == null) {
                endInput.error = "Format attendu : HH:mm"
                return@setOnClickListener
            }
            val endMs = normalizeEnd(startMs, rawEndMs)
            if (endMs == null) {
                endInput.error = "Le début et la fin ne peuvent pas être identiques"
                return@setOnClickListener
            }

            val companyId = companyByButtonId[companyGroup.checkedRadioButtonId]
            val place = placeInput.text.toString().trim()
            val added = if (companyId == null) {
                V2ManualSessionWriter.addWithoutCompany(activity, startMs, endMs, place)
            } else {
                V2ManualSessionWriter.addForCompany(activity, startMs, endMs, companyId, place)
            }
            if (!added) {
                Toast.makeText(
                    activity,
                    "Plage non ajoutée : doublon, données invalides ou historique V2 à vérifier.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            PointageWidgetProvider.updateAll(activity)
            QuickActionsWidgetProvider.updateAll(activity)
            V2BackupManager.backupIfConfiguredAsync(activity)
            Toast.makeText(
                activity,
                "Heures ajoutées : ${formatDuration(endMs - startMs)}",
                Toast.LENGTH_LONG
            ).show()
            dialog.dismiss()
            activity.recreate()
        }
        dialog.show()
    }

    internal fun parseTime(day: Calendar, raw: String): Long? {
        val match = Regex("^\\s*(\\d{1,2})[:hH](\\d{2})\\s*$").matchEntire(raw) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    internal fun normalizeEnd(
        startMs: Long,
        rawEndMs: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long? = when {
        rawEndMs == startMs -> null
        rawEndMs < startMs -> Calendar.getInstance(timeZone).apply {
            timeInMillis = rawEndMs
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        else -> rawEndMs
    }

    private fun themedInput(
        context: Context,
        colors: V2ManualDialogColors,
        hintText: String
    ) = EditText(context).apply {
        hint = hintText
        isSingleLine = true
        setTextColor(colors.text)
        setHintTextColor(colors.secondary)
        backgroundTintList = ColorStateList.valueOf(colors.gold)
        setPadding(dp(context, 6), dp(context, 12), dp(context, 6), dp(context, 8))
    }

    private fun companyRadio(
        context: Context,
        colors: V2ManualDialogColors,
        label: String
    ) = RadioButton(context).apply {
        id = View.generateViewId()
        text = label
        setTextColor(colors.text)
        buttonTintList = ColorStateList.valueOf(colors.gold)
    }

    private fun dialogColors(context: Context): V2ManualDialogColors {
        val prefs = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val mode = prefs.getString("mode", "auto") ?: "auto"
        val systemDark = (
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            ) == Configuration.UI_MODE_NIGHT_YES
        val dark = mode == "dark" || (mode == "auto" && systemDark)
        return V2ManualDialogColors(
            background = Color.parseColor(if (dark) "#0B0B0B" else "#F7F3EA"),
            panel = Color.parseColor(if (dark) "#181818" else "#FFFFFF"),
            text = Color.parseColor(if (dark) "#F4EFE3" else "#17130D"),
            secondary = Color.parseColor(if (dark) "#CFC7B8" else "#625B50"),
            gold = Color.parseColor("#D6A84B"),
            goldLight = Color.parseColor(if (dark) "#F3D58A" else "#795600")
        )
    }

    private fun rounded(
        context: Context,
        color: Int,
        radiusDp: Int,
        stroke: Int? = null
    ) = GradientDrawable().apply {
        val density = context.resources.displayMetrics.density
        setColor(color)
        cornerRadius = radiusDp * density
        if (stroke != null) setStroke(density.toInt().coerceAtLeast(1), stroke)
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
        return String.format(Locale.FRANCE, "%02dh %02dm", totalMinutes / 60L, totalMinutes % 60L)
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()
}
