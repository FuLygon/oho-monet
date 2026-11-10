package com.samsung.android.gtscell;

import android.os.Binder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Callback object expected by the One Hand Operation+ GTS provider.
 *
 * <p>The provider unmarshals the {@code finish_callback} extra with its own class
 * loader, so this class has to live in the same package and use the same name. The
 * parcel only carries a strong binder, which is all the provider reads. The app does
 * not need the write result, so the binder is never called back.</p>
 */
public class RemoteCallback extends Binder implements Parcelable {

    public static final Parcelable.Creator<RemoteCallback> CREATOR =
            new Parcelable.Creator<RemoteCallback>() {
                @Override
                public RemoteCallback createFromParcel(Parcel in) {
                    return new RemoteCallback();
                }

                @Override
                public RemoteCallback[] newArray(int size) {
                    return new RemoteCallback[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeStrongBinder(this);
    }
}
