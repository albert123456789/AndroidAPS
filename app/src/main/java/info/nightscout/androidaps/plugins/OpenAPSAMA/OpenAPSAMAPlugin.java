package info.nightscout.androidaps.plugins.OpenAPSAMA;

import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Date;

import info.nightscout.androidaps.Config;
import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.GlucoseStatus;
import info.nightscout.androidaps.data.IobTotal;
import info.nightscout.androidaps.data.MealData;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.interfaces.APSInterface;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PumpInterface;
import info.nightscout.androidaps.plugins.IobCobCalculator.AutosensResult;
import info.nightscout.androidaps.plugins.IobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.plugins.Loop.APSResult;
import info.nightscout.androidaps.plugins.Loop.ScriptReader;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.plugins.OpenAPSMA.events.EventOpenAPSUpdateGui;
import info.nightscout.androidaps.plugins.OpenAPSMA.events.EventOpenAPSUpdateResultGui;
import info.nightscout.utils.DateUtil;
import info.nightscout.utils.NSUpload;
import info.nightscout.utils.Profiler;
import info.nightscout.utils.Round;
import info.nightscout.utils.SP;
import info.nightscout.utils.SafeParse;
import info.nightscout.utils.ToastUtils;

/**
 * Created by mike on 05.08.2016.
 */
public class OpenAPSAMAPlugin implements PluginBase, APSInterface {
    private static Logger log = LoggerFactory.getLogger(OpenAPSAMAPlugin.class);

    // last values
    DetermineBasalAdapterAMAJS lastDetermineBasalAdapterAMAJS = null;
    Date lastAPSRun = null;
    DetermineBasalResultAMA lastAPSResult = null;
    AutosensResult lastAutosensResult = null;

    boolean fragmentEnabled = false;
    boolean fragmentVisible = true;

    @Override
    public String getName() {
        return MainApp.instance().getString(R.string.openapsama);
    }

    @Override
    public String getNameShort() {
        String name = MainApp.sResources.getString(R.string.oaps_shortname);
        if (!name.trim().isEmpty()) {
            //only if translation exists
            return name;
        }
        // use long name as fallback
        return getName();
    }

    @Override
    public boolean isEnabled(int type) {
        return type == APS && fragmentEnabled && MainApp.getConfigBuilder().getPumpDescription().isTempBasalCapable;
    }

    @Override
    public boolean isVisibleInTabs(int type) {
        return type == APS && fragmentVisible && MainApp.getConfigBuilder().getPumpDescription().isTempBasalCapable;
    }

    @Override
    public boolean canBeHidden(int type) {
        return true;
    }

    @Override
    public boolean hasFragment() {
        return true;
    }

    @Override
    public boolean showInList(int type) {
        return true;
    }

    @Override
    public void setFragmentVisible(int type, boolean fragmentVisible) {
        if (type == APS) this.fragmentVisible = fragmentVisible;
    }

    @Override
    public void setFragmentEnabled(int type, boolean fragmentEnabled) {
        if (type == APS) this.fragmentEnabled = fragmentEnabled;
    }

    @Override
    public int getType() {
        return PluginBase.APS;
    }

    @Override
    public String getFragmentClass() {
        return OpenAPSAMAFragment.class.getName();
    }

    @Override
    public APSResult getLastAPSResult() {
        return lastAPSResult;
    }

    @Override
    public Date getLastAPSRun() {
        return lastAPSRun;
    }

    @Override
    public void invoke(String initiator) {
        log.debug("invoke from " + initiator);
        lastAPSResult = null;
        DetermineBasalAdapterAMAJS determineBasalAdapterAMAJS = null;
        try {
            determineBasalAdapterAMAJS = new DetermineBasalAdapterAMAJS(new ScriptReader(MainApp.instance().getBaseContext()));
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            return;
        }

        GlucoseStatus glucoseStatus = GlucoseStatus.getGlucoseStatusData();
        Profile profile = MainApp.getConfigBuilder().getProfile();
        PumpInterface pump = MainApp.getConfigBuilder();

        if (profile == null) {
            MainApp.bus().post(new EventOpenAPSUpdateResultGui(MainApp.instance().getString(R.string.noprofileselected)));
            if (Config.logAPSResult)
                log.debug(MainApp.instance().getString(R.string.noprofileselected));
            return;
        }

        if (!isEnabled(PluginBase.APS)) {
            MainApp.bus().post(new EventOpenAPSUpdateResultGui(MainApp.instance().getString(R.string.openapsma_disabled)));
            if (Config.logAPSResult)
                log.debug(MainApp.instance().getString(R.string.openapsma_disabled));
            return;
        }

        if (glucoseStatus == null) {
            MainApp.bus().post(new EventOpenAPSUpdateResultGui(MainApp.instance().getString(R.string.openapsma_noglucosedata)));
            if (Config.logAPSResult)
                log.debug(MainApp.instance().getString(R.string.openapsma_noglucosedata));
            return;
        }

        String units = profile.getUnits();

        double maxBgDefault = Constants.MAX_BG_DEFAULT_MGDL;
        double minBgDefault = Constants.MIN_BG_DEFAULT_MGDL;
        double targetBgDefault = Constants.TARGET_BG_DEFAULT_MGDL;
        if (!units.equals(Constants.MGDL)) {
            maxBgDefault = Constants.MAX_BG_DEFAULT_MMOL;
            minBgDefault = Constants.MIN_BG_DEFAULT_MMOL;
            targetBgDefault = Constants.TARGET_BG_DEFAULT_MMOL;
        }

        Date now = new Date();

        double maxIob = SP.getDouble("openapsma_max_iob", 1.5d);
        double maxBasal = SP.getDouble("openapsma_max_basal", 1d);
        double minBg = Profile.toMgdl(SP.getDouble("openapsma_min_bg", minBgDefault), units);
        double maxBg = Profile.toMgdl(SP.getDouble("openapsma_max_bg", maxBgDefault), units);
        double targetBg = Profile.toMgdl(SP.getDouble("openapsma_target_bg", targetBgDefault), units);

        minBg = Round.roundTo(minBg, 0.1d);
        maxBg = Round.roundTo(maxBg, 0.1d);

        Date start = new Date();
        Date startPart = new Date();
        IobTotal[] iobArray = IobCobCalculatorPlugin.calculateIobArrayInDia();
        Profiler.log(log, "calculateIobArrayInDia()", startPart);

        startPart = new Date();
        MealData mealData = MainApp.getConfigBuilder().getMealData();
        Profiler.log(log, "getMealData()", startPart);

        maxIob = MainApp.getConfigBuilder().applyMaxIOBConstraints(maxIob);

        minBg = verifyHardLimits(minBg, "minBg", Constants.VERY_HARD_LIMIT_MIN_BG[0], Constants.VERY_HARD_LIMIT_MIN_BG[1]);
        maxBg = verifyHardLimits(maxBg, "maxBg", Constants.VERY_HARD_LIMIT_MAX_BG[0], Constants.VERY_HARD_LIMIT_MAX_BG[1]);
        targetBg = verifyHardLimits(targetBg, "targetBg", Constants.VERY_HARD_LIMIT_TARGET_BG[0], Constants.VERY_HARD_LIMIT_TARGET_BG[1]);

        boolean isTempTarget = false;
        TempTarget tempTarget = MainApp.getConfigBuilder().getTempTargetFromHistory(System.currentTimeMillis());
        if (tempTarget != null) {
            isTempTarget = true;
            minBg = verifyHardLimits(tempTarget.low, "minBg", Constants.VERY_HARD_LIMIT_TEMP_MIN_BG[0], Constants.VERY_HARD_LIMIT_TEMP_MIN_BG[1]);
            maxBg = verifyHardLimits(tempTarget.high, "maxBg", Constants.VERY_HARD_LIMIT_TEMP_MAX_BG[0], Constants.VERY_HARD_LIMIT_TEMP_MAX_BG[1]);
            targetBg = verifyHardLimits((tempTarget.low + tempTarget.high) / 2, "targetBg", Constants.VERY_HARD_LIMIT_TEMP_TARGET_BG[0], Constants.VERY_HARD_LIMIT_TEMP_TARGET_BG[1]);
        }


        maxIob = verifyHardLimits(maxIob, "maxIob", 0, 7);
        maxBasal = verifyHardLimits(maxBasal, "max_basal", 0.1, 10);

        if (!checkOnlyHardLimits(profile.getDia(), "dia", 2, 7)) return;
        if (!checkOnlyHardLimits(profile.getIc(profile.secondsFromMidnight()), "carbratio", 2, 100))
            return;
        if (!checkOnlyHardLimits(Profile.toMgdl(profile.getIsf().doubleValue(), units), "sens", 2, 900))
            return;
        if (!checkOnlyHardLimits(profile.getMaxDailyBasal(), "max_daily_basal", 0.1, 10)) return;
        if (!checkOnlyHardLimits(pump.getBaseBasalRate(), "current_basal", 0.01, 5)) return;

        startPart = new Date();
        if (MainApp.getConfigBuilder().isAMAModeEnabled()) {
            lastAutosensResult = IobCobCalculatorPlugin.detectSensitivityWithLock(IobCobCalculatorPlugin.oldestDataAvailable(), System.currentTimeMillis());
        } else {
            lastAutosensResult = new AutosensResult();
        }
        Profiler.log(log, "detectSensitivityandCarbAbsorption()", startPart);
        Profiler.log(log, "AMA data gathering", start);

        start = new Date();
        determineBasalAdapterAMAJS.setData(profile, maxIob, maxBasal, minBg, maxBg, targetBg, pump, iobArray, glucoseStatus, mealData,
                lastAutosensResult.ratio, //autosensDataRatio
                isTempTarget,
                SafeParse.stringToDouble(SP.getString("openapsama_min_5m_carbimpact", "3.0"))//min_5m_carbimpact
        );


        DetermineBasalResultAMA determineBasalResultAMA = determineBasalAdapterAMAJS.invoke();
        Profiler.log(log, "AMA calculation", start);
        // Fix bug determine basal
        if (determineBasalResultAMA.rate == 0d && determineBasalResultAMA.duration == 0 && !MainApp.getConfigBuilder().isTempBasalInProgress())
            determineBasalResultAMA.changeRequested = false;
        // limit requests on openloop mode
        if (!MainApp.getConfigBuilder().isClosedModeEnabled()) {
            if (MainApp.getConfigBuilder().isTempBasalInProgress() && Math.abs(determineBasalResultAMA.rate - MainApp.getConfigBuilder().getTempBasalAbsoluteRateHistory()) < 0.1)
                determineBasalResultAMA.changeRequested = false;
            if (!MainApp.getConfigBuilder().isTempBasalInProgress() && Math.abs(determineBasalResultAMA.rate - MainApp.getConfigBuilder().getBaseBasalRate()) < 0.1)
                determineBasalResultAMA.changeRequested = false;
        }

        determineBasalResultAMA.iob = iobArray[0];

        determineBasalAdapterAMAJS.release();

        try {
            determineBasalResultAMA.json.put("timestamp", DateUtil.toISOString(now));
        } catch (JSONException e) {
            e.printStackTrace();
        }

        lastDetermineBasalAdapterAMAJS = determineBasalAdapterAMAJS;
        lastAPSResult = determineBasalResultAMA;
        lastAPSRun = now;
        MainApp.bus().post(new EventOpenAPSUpdateGui());

        //deviceStatus.suggested = determineBasalResultAMA.json;
    }

    // safety checks
    public static boolean checkOnlyHardLimits(Double value, String valueName, double lowLimit, double highLimit) {
        return value.equals(verifyHardLimits(value, valueName, lowLimit, highLimit));
    }

    public static Double verifyHardLimits(Double value, String valueName, double lowLimit, double highLimit) {
        Double newvalue = value;
        if (newvalue < lowLimit || newvalue > highLimit) {
            newvalue = Math.max(newvalue, lowLimit);
            newvalue = Math.min(newvalue, highLimit);
            String msg = String.format(MainApp.sResources.getString(R.string.openapsma_valueoutofrange), valueName);
            msg += ".\n";
            msg += String.format(MainApp.sResources.getString(R.string.openapsma_valuelimitedto), value, newvalue);
            log.error(msg);
            NSUpload.uploadError(msg);
            ToastUtils.showToastInUiThread(MainApp.instance().getApplicationContext(), msg, R.raw.error);
        }
        return newvalue;
    }

}
