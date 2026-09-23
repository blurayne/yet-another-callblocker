package net.evolution515.callblocker.data;

import android.content.Context;

import androidx.annotation.StringRes;

import net.evolution515.callblocker.R;
import net.evolution515.callblocker.data.numbers.NumbersLookup;
import dummydomain.yetanothercallblocker.sia.model.NumberCategory;

public class SiaNumberCategoryUtils {

    /**
     * What a category id means, whoever it came from.
     *
     * <p>The library has nineteen of them and a translation for each. A source that brings
     * its own is given ids beyond those as they arrive, and what those mean is in the table
     * the sources were built into - under the name the source used, because a category that
     * didn't exist until this morning has no translation to show instead.
     *
     * @return the name, or null when the number means nothing to anybody
     */
    public static String getName(Context context, int id) {
        NumberCategory category = NumberCategory.getById(id);

        if (category != null) {
            return category != NumberCategory.NONE ? getName(context, category) : null;
        }

        NumbersLookup lookup = YacbHolder.getNumbersLookup();

        return lookup != null ? lookup.categoryName(id) : null;
    }

    public static String getName(Context context, NumberCategory category) {
        return context.getString(getNameResId(category));
    }

    @StringRes
    public static int getNameResId(NumberCategory category) {
        if (category == null) {
            return R.string.sia_category_none;
        }

        switch (category) {
            case NONE: return R.string.sia_category_none;
            case TELEMARKETER: return R.string.sia_category_telemarketer;
            case DEPT_COLLECTOR: return R.string.sia_category_dept_collector;
            case SILENT_CALL: return R.string.sia_category_silent;
            case NUISANCE_CALL: return R.string.sia_category_nuisance;
            case UNSOLICITED_CALL: return R.string.sia_category_unsolicited;
            case CALL_CENTER: return R.string.sia_category_call_center;
            case FAX_MACHINE: return R.string.sia_category_fax;
            case NON_PROFIT: return R.string.sia_category_nonprofit;
            case POLITICAL: return R.string.sia_category_political;
            case SCAM: return R.string.sia_category_scam;
            case PRANK: return R.string.sia_category_prank;
            case SMS: return R.string.sia_category_sms;
            case SURVEY: return R.string.sia_category_survey;
            case OTHER: return R.string.sia_category_other;
            case FINANCE_SERVICE: return R.string.sia_category_financial_service;
            case COMPANY: return R.string.sia_category_company;
            case SERVICE: return R.string.sia_category_service;
            case ROBOCALL: return R.string.sia_category_robocall;
            // TODO: check: these are probably not present in the db
            case SAFE_PERSONAL: return R.string.sia_category_safe_personal;
            case SAFE_COMPANY: return R.string.sia_category_safe_company;
            case SAFE_NONPROFIT: return R.string.sia_category_safe_nonprofit;
            default: throw new RuntimeException("Category not implemented: " + category);
        }
    }

}
