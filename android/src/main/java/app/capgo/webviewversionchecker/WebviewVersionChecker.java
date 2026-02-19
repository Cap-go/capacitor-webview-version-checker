package app.capgo.webviewversionchecker;

import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

final class WebviewVersionChecker {

    @Nullable
    Integer parseMajorVersion(@Nullable String version) {
        if (version == null || version.isEmpty()) {
            return null;
        }

        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (digits.length() > 0) {
                break;
            }
        }

        if (digits.length() == 0) {
            return null;
        }

        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    int compareVersions(@Nullable String left, @Nullable String right) {
        List<Integer> leftParts = tokenize(left);
        List<Integer> rightParts = tokenize(right);

        int max = Math.max(leftParts.size(), rightParts.size());
        for (int index = 0; index < max; index++) {
            int a = index < leftParts.size() ? leftParts.get(index) : 0;
            int b = index < rightParts.size() ? rightParts.get(index) : 0;
            if (a > b) {
                return 1;
            }
            if (a < b) {
                return -1;
            }
        }

        return 0;
    }

    private List<Integer> tokenize(@Nullable String value) {
        List<Integer> out = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return out;
        }

        String[] rawParts = value.split("[^0-9]+");
        for (String part : rawParts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            try {
                out.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
                out.add(0);
            }
        }

        return out;
    }
}
