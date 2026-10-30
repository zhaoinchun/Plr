package androidx.media3.common;

import static android.os.Build.VERSION.SDK_INT;
import static com.google.common.base.Preconditions.checkElementIndex;
import static com.google.common.base.Preconditions.checkState;

import android.util.SparseBooleanArray;

import androidx.annotation.Nullable;

public final class FlagSet {
    public static final class Builder {

        private final SparseBooleanArray flags;

        private boolean buildCalled;

        public Builder() {
            flags = new SparseBooleanArray();
        }

        public Builder add(int flag) {
            checkState(!buildCalled);
            flags.append(flag,  true);
            return this;
        }

        public Builder addIf(int flag, boolean condition) {
            if (condition) {
                return add(flag);
            }
            return this;
        }

        public Builder addAll(int... flags) {
            for (int flag : flags) {
                add(flag);
            }
            return this;
        }

        public Builder addAll(FlagSet flags) {
            for (int i = 0; i < flags.size(); i++) {
                add(flags.get(i));
            }
            return this;
        }

        public Builder remove(int flag) {
            checkState(!buildCalled);
            flags.delete(flag);
            return this;
        }

        public Builder removeIf(int flag, boolean condition) {
            if (condition) {
                return remove(flag);
            }
            return this;
        }

        public Builder removeAll(int... flags) {
            for (int flag : flags) {
                remove(flag);
            }
            return this;
        }

        public FlagSet build() {
            checkState(!buildCalled);
            buildCalled = true;
            return new FlagSet(flags);
        }
    }

    // A SparseBooleanArray is used instead of a Set to avoid auto-boxing the flag values.
    private final SparseBooleanArray flags;

    private FlagSet(SparseBooleanArray flags) {
        this.flags = flags;
    }

    public boolean contains(int flag) {
        return flags.get(flag);
    }

    public boolean containsAny(int[] flags) {
        for (int flag : flags) {
            if (contains(flag)) {
                return true;
            }
        }
        return false;
    }

    public boolean containsAny(FlagSet other) {
        for (int i = 0; i < other.size(); i++) {
            if (contains(other.get(i))) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return flags.size();
    }

    public int get(int index) {
        checkElementIndex(index, size());
        return flags.keyAt(index);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FlagSet)) {
            return false;
        }
        FlagSet that = (FlagSet) o;
        if (SDK_INT < 24) {
            // SparseBooleanArray.equals() is not implemented on API levels below 24.
            if (size() != that.size()) {
                return false;
            }
            for (int i = 0; i < size(); i++) {
                if (get(i) != that.get(i)) {
                    return false;
                }
            }
            return true;
        } else {
            return flags.equals(that.flags);
        }
    }

    @Override
    public int hashCode() {
        if (SDK_INT < 24) {
            // SparseBooleanArray.hashCode() is not implemented on API levels below 24.
            int hashCode = size();
            for (int i = 0; i < size(); i++) {
                hashCode = 31 * hashCode + get(i);
            }
            return hashCode;
        } else {
            return flags.hashCode();
        }
    }
}
