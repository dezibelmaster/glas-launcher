package tv.projectivy.plugin.wallpaperprovider.api;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * What the launcher tells a Projectivy plugin when it asks for wallpapers. Java copy of the
 * Kotlin sealed class in https://github.com/spocky/projectivy-plugin-wallpaper-provider
 * (Apache License 2.0). The launcher only sends the "time elapsed" event; @Parcelize writes
 * that Kotlin object as a single int 1, which is also its event type.
 */
public final class Event implements Parcelable {
    public static final int TIME_ELAPSED = 1;

    public final int type;

    private Event(int type) {
        this.type = type;
    }

    public static Event timeElapsed() {
        return new Event(TIME_ELAPSED);
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(type);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<Event> CREATOR = new Creator<Event>() {
        @Override
        public Event createFromParcel(Parcel in) {
            return new Event(in.readInt());
        }

        @Override
        public Event[] newArray(int size) {
            return new Event[size];
        }
    };
}
