package app.tveye;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;

import androidx.annotation.NonNull;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PromptDelegate;

import java.util.ArrayList;
import java.util.List;

/**
 * Page dialogs as plain TV dialogs (the remote's arrows work in them natively).
 * Logins, file pickers and pop-ups are refused: tveye is for watching, not for accounts.
 */
final class Prompts implements PromptDelegate {
    private final Activity act;

    Prompts(Activity a) { act = a; }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert);
    }

    @Override
    public GeckoResult<PromptResponse> onAlertPrompt(@NonNull GeckoSession s, @NonNull AlertPrompt p) {
        GeckoResult<PromptResponse> r = new GeckoResult<>();
        dialog().setTitle(p.title).setMessage(p.message)
                .setPositiveButton(R.string.ok, null)
                .setOnDismissListener(d -> r.complete(p.dismiss()))
                .show();
        return r;
    }

    @Override
    public GeckoResult<PromptResponse> onButtonPrompt(@NonNull GeckoSession s, @NonNull ButtonPrompt p) {
        GeckoResult<PromptResponse> r = new GeckoResult<>();
        final boolean[] done = {false};
        dialog().setTitle(p.title).setMessage(p.message)
                .setPositiveButton(R.string.ok, (d, w) -> { done[0] = true; r.complete(p.confirm(ButtonPrompt.Type.POSITIVE)); })
                .setNegativeButton(R.string.cancel, (d, w) -> { done[0] = true; r.complete(p.confirm(ButtonPrompt.Type.NEGATIVE)); })
                .setOnDismissListener(d -> { if (!done[0]) r.complete(p.dismiss()); })
                .show();
        return r;
    }

    @Override
    public GeckoResult<PromptResponse> onTextPrompt(@NonNull GeckoSession s, @NonNull TextPrompt p) {
        GeckoResult<PromptResponse> r = new GeckoResult<>();
        EditText input = new EditText(act);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        if (p.defaultValue != null) input.setText(p.defaultValue);
        final boolean[] done = {false};
        dialog().setTitle(p.title).setMessage(p.message).setView(input)
                .setPositiveButton(R.string.ok, (d, w) -> { done[0] = true; r.complete(p.confirm(input.getText().toString())); })
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(d -> { if (!done[0]) r.complete(p.dismiss()); })
                .show();
        return r;
    }

    @Override
    public GeckoResult<PromptResponse> onChoicePrompt(@NonNull GeckoSession s, @NonNull ChoicePrompt p) {
        GeckoResult<PromptResponse> r = new GeckoResult<>();
        List<ChoicePrompt.Choice> flat = new ArrayList<>();
        flatten(p.choices, flat);
        if (flat.isEmpty()) return GeckoResult.fromValue(p.dismiss());
        CharSequence[] labels = new CharSequence[flat.size()];
        int checked = -1;
        for (int i = 0; i < flat.size(); i++) {
            labels[i] = flat.get(i).label;
            if (flat.get(i).selected && checked < 0) checked = i;
        }
        final boolean[] done = {false};
        dialog().setTitle(p.title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    done[0] = true;
                    r.complete(p.confirm(flat.get(which)));
                    d.dismiss();
                })
                .setOnDismissListener(d -> { if (!done[0]) r.complete(p.dismiss()); })
                .show();
        return r;
    }

    private static void flatten(ChoicePrompt.Choice[] in, List<ChoicePrompt.Choice> out) {
        if (in == null) return;
        for (ChoicePrompt.Choice c : in) {
            if (c.items != null) flatten(c.items, out);
            else if (!c.disabled && !c.separator) out.add(c);
        }
    }

    @Override
    public GeckoResult<PromptResponse> onBeforeUnloadPrompt(@NonNull GeckoSession s, @NonNull BeforeUnloadPrompt p) {
        return GeckoResult.fromValue(p.confirm(AllowOrDeny.ALLOW));
    }

    @Override
    public GeckoResult<PromptResponse> onRepostConfirmPrompt(@NonNull GeckoSession s, @NonNull RepostConfirmPrompt p) {
        return GeckoResult.fromValue(p.confirm(AllowOrDeny.ALLOW));
    }

    @Override
    public GeckoResult<PromptResponse> onPopupPrompt(@NonNull GeckoSession s, @NonNull PopupPrompt p) {
        return GeckoResult.fromValue(p.confirm(AllowOrDeny.DENY));
    }

    @Override
    public GeckoResult<PromptResponse> onAuthPrompt(@NonNull GeckoSession s, @NonNull AuthPrompt p) {
        return GeckoResult.fromValue(p.dismiss());
    }

    @Override
    public GeckoResult<PromptResponse> onFilePrompt(@NonNull GeckoSession s, @NonNull FilePrompt p) {
        return GeckoResult.fromValue(p.dismiss());
    }
}
