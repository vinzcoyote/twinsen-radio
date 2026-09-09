<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="?attr/colorPrimary">

    <com.google.android.material.appbar.MaterialToolbar
        android:id="@+id/toolbar"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        android:background="?attr/colorPrimary"
        app:navigationIcon="@drawable/ic_arrow_back"
        app:title="@string/now_playing_title"
        app:titleTextColor="@color/on_brand">

        <ImageButton
            android:id="@+id/favourite"
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:layout_gravity="end"
            android:layout_marginEnd="8dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="@string/fav_add"
            android:src="@drawable/ic_star_outline" />

    </com.google.android.material.appbar.MaterialToolbar>

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:orientation="vertical"
        android:background="?attr/colorSurface">

        <ImageView
            android:id="@+id/art"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_marginHorizontal="32dp"
            android:layout_marginTop="16dp"
            android:layout_weight="1"
            android:adjustViewBounds="true"
            android:contentDescription="@null"
            android:scaleType="fitCenter"
            android:src="@drawable/logo_placeholder" />

        <TextView
            android:id="@+id/stationName"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginHorizontal="24dp"
            android:layout_marginTop="12dp"
            android:ellipsize="end"
            android:gravity="center"
            android:maxLines="1"
            android:textAppearance="?attr/textAppearanceHeadlineMedium"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/songTitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginHorizontal="24dp"
            android:layout_marginTop="8dp"
            android:gravity="center"
            android:maxLines="2"
            android:textAppearance="?attr/textAppearanceHeadlineSmall" />

        <!--
            Sam wykonawca. Jesli nie miesci sie na jednej linii, wolimy zmniejszyc
            czcionke (autosize) niz zawijac - przy dwoch liniach nie byloby juz
            widac, gdzie konczy sie wykonawca a zaczyna plyta na linii ponizej.
        -->
        <TextView
            android:id="@+id/songArtist"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginHorizontal="20dp"
            android:layout_marginTop="4dp"
            android:autoSizeMaxTextSize="18sp"
            android:autoSizeMinTextSize="12sp"
            android:autoSizeStepGranularity="1sp"
            android:autoSizeTextType="uniform"
            android:ellipsize="end"
            android:gravity="center"
            android:maxLines="1"
            android:textAppearance="?attr/textAppearanceTitleMedium" />

        <!--
            Plyta i rok zawsze pod wykonawca, na osobnej linii - w jednej linii ze
            spojnikiem rok brzydko przeskakiwal sam do nowego wiersza przy dluzszych
            nazwach. Na Android Auto nie ma tego problemu (jedno pole, bez zawijania),
            wiec tam LineContent.ARTIST_ALBUM dalej sklejamy w jedna linie.
        -->
        <TextView
            android:id="@+id/songAlbum"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginHorizontal="20dp"
            android:layout_marginTop="2dp"
            android:ellipsize="end"
            android:gravity="center"
            android:maxLines="2"
            android:textAppearance="?attr/textAppearanceBodyMedium"
            android:visibility="gone" />

        <TextView
            android:id="@+id/status"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginHorizontal="16dp"
            android:layout_marginTop="8dp"
            android:gravity="center"
            android:textAppearance="?attr/textAppearanceLabelMedium" />

        <!--
            Wybor jakosci wprost z ekranu odtwarzania - ta sama nastawa, co w karcie
            stacji, wiec zmiana w jednym miejscu widac w drugim. Stukniecie otwiera
            dialog z lista wariantow (StreamPicker), a nie rozwijana liste w miejscu -
            przy dluzszych etykietach ta wygladala niechlujnie.
        -->
        <com.google.android.material.button.MaterialButton
            android:id="@+id/bitrate"
            style="@style/Widget.Material3.Button.TonalButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center_horizontal"
            android:layout_marginTop="8dp"
            android:ellipsize="end"
            android:insetTop="0dp"
            android:insetBottom="0dp"
            android:maxLines="1"
            android:minWidth="0dp"
            android:paddingHorizontal="8dp"
            android:paddingVertical="2dp"
            android:visibility="gone"
            app:backgroundTint="?attr/colorSurfaceContainerLow"
            app:cornerRadius="10dp"
            app:icon="@drawable/ic_expand_more"
            app:iconGravity="end"
            app:iconPadding="2dp"
            app:iconSize="14dp"
            app:iconTint="?attr/colorOnSurfaceVariant" />

        <LinearLayout
            android:id="@+id/diagnosticBanner"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginHorizontal="24dp"
            android:layout_marginTop="10dp"
            android:orientation="vertical"
            android:visibility="gone">

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center"
                android:letterSpacing="0.08"
                android:text="@string/diagnostic_banner_line1"
                android:textAppearance="?attr/textAppearanceLabelLarge"
                android:textColor="@color/diagnostic"
                android:textStyle="bold" />

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center"
                android:text="@string/diagnostic_banner_line2"
                android:textAppearance="?attr/textAppearanceBodySmall"
                android:textColor="@color/diagnostic" />

        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginVertical="12dp"
            android:gravity="center"
            android:orientation="horizontal">

            <ImageButton
                android:id="@+id/prev"
                android:layout_width="64dp"
                android:layout_height="64dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="@string/prev_station"
                android:src="@android:drawable/ic_media_previous" />

            <ImageButton
                android:id="@+id/playPause"
                android:layout_width="88dp"
                android:layout_height="88dp"
                android:layout_marginHorizontal="24dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="@null"
                android:src="@android:drawable/ic_media_play" />

            <ImageButton
                android:id="@+id/next"
                android:layout_width="64dp"
                android:layout_height="64dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:contentDescription="@string/next_station"
                android:src="@android:drawable/ic_media_next" />

        </LinearLayout>

    </LinearLayout>

</LinearLayout>
