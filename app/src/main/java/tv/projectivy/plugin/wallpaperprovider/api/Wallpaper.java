package tv.projectivy.plugin.wallpaperprovider.api;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A wallpaper returned by a Projectivy plugin. Java copy of the Kotlin @Parcelize class in
 * https://github.com/spocky/projectivy-plugin-wallpaper-provider (Apache License 2.0);
 * the fields are read and written in the same order as there.
 */
public final class Wallpaper implements Parcelable {
    public static final int IMAGE = 0, DRAWABLE = 1, ANIMATED_DRAWABLE = 2, LOTTIE = 3, VIDEO = 4, COLOR = 5;

    public final String uri;
    public final int type;
    public final int displayMode;
    public final String title;
    public final String source;
    public final String author;
    public final String actionUri;

    private Wallpaper(Parcel in) {
        uri = in.readString();
        type = in.readInt();
        displayMode = in.readInt();
        title = in.readString();
        source = in.readString();
        author = in.readString();
        actionUri = in.readString();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(uri);
        out.writeInt(type);
        out.writeInt(displayMode);
        out.writeString(title);
        out.writeString(source);
        out.writeString(author);
        out.writeString(actionUri);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<Wallpaper> CREATOR = new Creator<Wallpaper>() {
        @Override
        public Wallpaper createFromParcel(Parcel in) {
            return new Wallpaper(in);
        }

        @Override
        public Wallpaper[] newArray(int size) {
            return new Wallpaper[size];
        }
    };
}
