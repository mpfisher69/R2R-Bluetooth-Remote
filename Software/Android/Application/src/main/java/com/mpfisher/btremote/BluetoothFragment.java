/*
 * Copyright (C) 2021 mpfisher.com R2R BT Remote Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
/*
 *
 * v.1.0.7 2021/09/26
 * Implemented REC_FWD and REC_REV commands
 * Added tap&hold event handler for REC button
 * REC_ARMED button removed
 * Added LED brightness settings option
 * All command values changed. !!!Update the receiver!!!
 * Added scaling of the function buttons for different screen sizes
 *
 * TODO
 *  Implement state machine sync.
 *  Drop the use of fragments and move everything to the main activity.
 *
 *
 */

package com.mpfisher.btremote;

import android.app.ActionBar;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

//import com.mpfisher.btremote.R;
import com.mpfisher.common.logger.Log;

/**
 * This fragment controls Bluetooth to communicate with other devices.
 */
public class BluetoothFragment extends Fragment {

    private static final String TAG = "BT-RemoteFragment";

    // Intent request codes
    private static final int REQUEST_CONNECT_DEVICE_SECURE = 1;
    private static final int REQUEST_CONNECT_DEVICE_INSECURE = 2;
    private static final int REQUEST_ENABLE_BT = 3;

    private ImageButton mRecButton;
    private ImageButton mRewButton;
    private ImageButton mPlayButton;
    private ImageButton mFfwdButton;
    private ImageButton mStopButton;
    private ImageButton mPauseButton;
    private ImageButton mAutoMuteButton;
    private ImageButton mPlayFwdButton;
    private ImageButton mPlayRevButton;

    private TextView machineNameLabel;
    // Tape transport functions
    private static final byte CMD_REC_FWD      = 0x01;
    private static final byte CMD_REC_REV      = 0x02;
    private static final byte CMD_PAUSE        = 0x03;
    private static final byte CMD_PLAY_FWD     = 0x04;
    private static final byte CMD_PLAY_REV     = 0x05;
    private static final byte CMD_STOP         = 0x06;
    private static final byte CMD_FFWD         = 0x07;
    private static final byte CMD_REW          = 0x08;
    private static final byte CMD_AUTO_MUTE    = 0x09;
    private static final byte CMD_TRANSPORT_FUNCTION   = (byte) (0xAA & 0xFF);
    // Receiver config
    private static final byte CMD_SET_REC_STDBY_MODE   = (byte) (0xBB & 0xFF);
    private static final byte REC_STDBY_MODE_TECHNICS  = 0x01;
    private static final byte REC_STDBY_MODE_AKAI      = 0x02;
    private static final byte REC_STDBY_MODE_PIONEER   = 0x04;
    private static final byte REC_STDBY_MODE_TASCAM    = 0x08;

    private static final byte CMD_SET_DELAY    = (byte) (0xBA & 0xFF);
    private static final byte CMD_SET_LED_PWM  = (byte) (0xCA & 0xFF);
    // Name of the connected device
    private String mConnectedDeviceName = null;
    // TODO: State machine sync.
    //private static final byte ACK = (byte)  (0xEF & 0xFF);

     // Byte buffer for outgoing messages
    //private byte[] mOutBuffer;

    /**
     * Local Bluetooth adapter
     */
    private BluetoothAdapter mBluetoothAdapter = null;

    /**
     * Member objects for BT services
     */
    private BluetoothService mBTService = null;

    private MenuItem menu_icon_bt = null;
    private boolean rec_on = false;
    private boolean rec_long_press = false;
    private byte delay = 100; // Holds delay value read from preferences
    private byte pwm = 100; // Holds pwm value (Bluetooth LED brightness) read from preferences
    private byte RecStdbyMode = REC_STDBY_MODE_TECHNICS;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        // Get local Bluetooth adapter
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // If the adapter is null, then Bluetooth is not supported
        FragmentActivity activity = getActivity();
        if (mBluetoothAdapter == null && activity != null) {
            Toast.makeText(activity, "Bluetooth is not available", Toast.LENGTH_LONG).show();
            activity.finish();
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        FragmentActivity activity = getActivity();

        // Read preference values and setup the app
        //
        // Machine name label. Set by user
        final String machineName = Utils.getPrefence(activity,
                getString(R.string.prefkey_machine_name));
        machineNameLabel.setText(machineName);
        Log.d(TAG, "Loaded prefkey_machine_name, \""+machineNameLabel.getText()+"\"");

        // Show "AUTO MUTE" button?
        if (Utils.getBooleanPrefence(activity,
                getString(R.string.prefkey_show_rec_mute))) {
            mAutoMuteButton.setVisibility(View.VISIBLE);
            Log.d(TAG, "Loaded prefkey_show_rec_mute, true");
        }else{
            mAutoMuteButton.setVisibility(View.INVISIBLE);
            Log.d(TAG, "Loaded prefkey_show_rec_mute, false");
        }

        // Show "PLAY_REV" button?
        if (Utils.getBooleanPrefence(activity,
                getString(R.string.prefkey_show_play_rev))) {
            mPlayButton.setVisibility(View.GONE);
            mPlayFwdButton.setVisibility(View.VISIBLE);
            mPlayRevButton.setVisibility(View.VISIBLE);
            Log.d(TAG, "Loaded prefkey_show_play_rev, true");
        }else{
            mPlayButton.setVisibility(View.VISIBLE);
            mPlayFwdButton.setVisibility(View.GONE);
            mPlayRevButton.setVisibility(View.GONE);
            Log.d(TAG, "Loaded prefkey_show_play_rev, false");
        }

        // Button press delay
        // Read the delay value from preference as string
        final String strDelay = Utils.getPrefence(activity, getString(R.string.prefkey_cmd_delay));
        Log.d(TAG, "Loaded prefkey_cmd_delay, " + strDelay +"ms");
        // Convert string to int
        int TempInt;
        try {
            TempInt = Integer.valueOf(strDelay);
        }
        catch (NumberFormatException nfe){
            TempInt = 100;
        }
        // Ensure delay value is at least 1ms and no more than 255ms
        if(TempInt > 255){
            TempInt = 255;
        }
        if(TempInt < 1) {
            TempInt = 1;
        }
        // Save delay value into global variable
        delay = (byte) (TempInt & 0xFF);

        // LED PWM value
        // Read the PWM value from preference as string
        final String strPWM = Utils.getPrefence(activity, getString(R.string.prefkey_cmd_pwm));
        Log.d(TAG, "Loaded prefkey_cmd_pwm, " + strPWM +"%");
        // Convert string to int
        try {
            TempInt = Integer.valueOf(strPWM);
        }
        catch (NumberFormatException nfe){
            TempInt = 100;
        }
        // Ensure PWM value is no more than 100% and no less than 0%
        if(TempInt > 100){
            TempInt = 100;
        }
        if(TempInt < 0) {
            TempInt = 0;
        }
        // Save PWM value into global variable
        pwm = (byte) (TempInt & 0xFF);

        // Rec Standby mode
        final String strRecStdbyMode = Utils.getPrefence(activity,
                getString(R.string.prefkey_rec_stdby_mode));
        switch (strRecStdbyMode) {
            case "Technics":
                RecStdbyMode = REC_STDBY_MODE_TECHNICS;
                break;
            case "Akai":
                RecStdbyMode = REC_STDBY_MODE_AKAI;
                break;
            case "Pioneer":
                RecStdbyMode = REC_STDBY_MODE_PIONEER;
                break;
            case "Tascam":
                RecStdbyMode = REC_STDBY_MODE_TASCAM;
                break;
            default:
                RecStdbyMode = REC_STDBY_MODE_TECHNICS;
                break;
        }
        Log.d(TAG, "Loaded prefkey_rec_stdby_mode, "+ String.valueOf(RecStdbyMode));

        // -------------------------------------------------------------

        if (mBluetoothAdapter == null) {
            return;
        }
        // If BT is not on, request that it be enabled.
        if (!mBluetoothAdapter.isEnabled()) {
            Toast.makeText(activity,
                    R.string.bt_enable_prompt, Toast.LENGTH_LONG).show();
            // Otherwise, setup the bluetooth session
        } else if (mBTService == null) {
            Log.d(TAG, "Attempting to setup Bluetooth connection...");
            setupBT_Remote();
        }
    }
    // -------------------------------------------------------------
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mBTService != null) {
            mBTService.stop();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // Performing this check in onResume() covers the case in which BT was
        // not enabled during onStart(), so we were paused to enable it...
        if (mBTService != null) {
            // Only if the state is STATE_NONE, do we know that we haven't started already
            if (mBTService.getState() == BluetoothService.STATE_NONE) {
                // Start the Bluetooth services
                mBTService.start();
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_bluetooth_remote, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {

        mRecButton = view.findViewById(R.id.btn_rec);
        mRewButton = view.findViewById(R.id.btn_rew);
        mPlayButton = view.findViewById(R.id.btn_play);
        mFfwdButton = view.findViewById(R.id.btn_ffwd);
        mStopButton = view.findViewById(R.id.btn_stop);
        mPauseButton = view.findViewById(R.id.btn_pause);
        mAutoMuteButton = view.findViewById(R.id.btnAutoMute);
        mPlayFwdButton = view.findViewById(R.id.btn_play_fwd);
        mPlayRevButton = view.findViewById(R.id.btn_play_rev);
        ImageView mSpacerButton1;
        mSpacerButton1 = view.findViewById(R.id.spacer1);
        ImageView mSpacerButton2;
        mSpacerButton2 = view.findViewById(R.id.spacer2);
        ImageView mSpacerButton3;
        mSpacerButton3 = view.findViewById(R.id.spacer3);

        // Set all buttons to "on" for demo
        mRecButton.setImageResource(R.drawable.btn_single_rec_on);
        mRewButton.setImageResource(R.drawable.btn_single_rew_on);
        mPlayButton.setImageResource(R.drawable.btn_double_play_on);
        mFfwdButton.setImageResource(R.drawable.btn_single_ffwd_on);
        mStopButton.setImageResource(R.drawable.btn_double_stop_on);
        mPauseButton.setImageResource(R.drawable.btn_single_pause_on);
        mAutoMuteButton.setImageResource(R.drawable.btn_single_auto_mute_on);
        mPlayFwdButton.setImageResource(R.drawable.btn_single_play_fwd_on);
        mPlayRevButton.setImageResource(R.drawable.btn_single_play_rev_on);

        machineNameLabel = view.findViewById(R.id.machine_name);

        // Scale the buttons for different screen sizes
        // Set the width of each button to 23% of the screen width
        int buttonSize = Math.round(MainActivity.ScreenSize * 0.23f);
        mSpacerButton1.setMaxWidth(buttonSize);
        mSpacerButton2.setMaxWidth(buttonSize);
        mSpacerButton3.setMaxWidth(buttonSize);
        mAutoMuteButton.setMaxWidth(buttonSize);
        mRecButton.setMaxWidth(buttonSize);
        mPlayButton.setMaxWidth(buttonSize * 2); // double-width button
        mPlayFwdButton.setMaxWidth(buttonSize);
        mPlayRevButton.setMaxWidth(buttonSize);
        mPauseButton.setMaxWidth(buttonSize);
        mRewButton.setMaxWidth(buttonSize);
        mStopButton.setMaxWidth(buttonSize * 2); // double-width button
        mFfwdButton.setMaxWidth(buttonSize);

    }

    /**
     * Set up the UI and background operations for BT-Remote.
     */
    private void setupBT_Remote() {
        Log.d(TAG, "setupBT_Remote()");

        // Initialize the array adapter for the conversation thread
        final FragmentActivity activity = getActivity();
        if (activity == null) {
            return;
        }

        Log.d(TAG, "setup OnClick listeners...");
        mRecButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.d(TAG, ">> RecOnClick");
                Toast.makeText(activity, "Hold REC and tap PLAY button",
                        Toast.LENGTH_LONG).show();
                // Short tap should not fire any events
            }
        });

        mRecButton.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                Log.d(TAG, ">> RecOnLongClick");
                rec_long_press = true;
                Toast.makeText(activity, "Tap PLAY button now...",
                        Toast.LENGTH_LONG).show();
                return true;
            }
        });

        mRewButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                byte[] buffer = {CMD_TRANSPORT_FUNCTION, CMD_REW};
                sendBytes(buffer);
                rec_on = false;
                IlluminateButton(mRewButton);
                Log.d(TAG, ">> CMD_REW");
            }
        });
        mPlayButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                byte[] buffer = new byte[2];
                buffer[0] = CMD_TRANSPORT_FUNCTION;
                Log.d(TAG, ">> PlayFwdOnClick");

                if(rec_long_press) { // If user selected REC Standby
                    rec_on = true;
                    IlluminateButton(mPauseButton);
                    buffer[1] = CMD_REC_FWD;
                    Log.d(TAG, ">> CMD_REC_FWD");
                    rec_long_press = false; // REC button long press event is now consumed
                }else{ // If user selected PLAY
                    IlluminateButton(mPlayButton);
                    buffer[1] = CMD_PLAY_FWD;
                    Log.d(TAG, ">> CMD_PLAY_FWD");
                }
                sendBytes(buffer);
            }
        });
        mPlayFwdButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                byte[] buffer = new byte[2];
                buffer[0] = CMD_TRANSPORT_FUNCTION;
                Log.d(TAG, ">> PlayFwdOnClick");

                if(rec_long_press) { // If user selected REC Standby
                    rec_on = true;
                    IlluminateButton(mPauseButton);
                    buffer[1] = CMD_REC_FWD;
                    Log.d(TAG, ">> CMD_REC_FWD");
                    rec_long_press = false; // REC button long press event is now consumed
                }else{ // If user selected PLAY
                    IlluminateButton(mPlayFwdButton);
                    buffer[1] = CMD_PLAY_FWD;
                    Log.d(TAG, ">> CMD_PLAY_FWD");
                }
                sendBytes(buffer);
            }
        });
        mPlayRevButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                byte[] buffer = new byte[2];
                buffer[0] = CMD_TRANSPORT_FUNCTION;
                Log.d(TAG, ">> PlayRevOnClick");

                if(rec_long_press) { // If user selected REC Standby
                    rec_on = true; // We are switching to REC Standby
                    IlluminateButton(mPauseButton);
                    buffer[1] = CMD_REC_REV;
                    Log.d(TAG, ">> CMD_REC_REV");
                    rec_long_press = false; // REC button long press event is now consumed
                }else{ // If user selected PLAY
                    IlluminateButton(mPlayRevButton);
                    buffer[1] = CMD_PLAY_REV;
                    Log.d(TAG, ">> CMD_PLAY_REV");
                }
                sendBytes(buffer);
            }
        });
        mFfwdButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                byte[] buffer = {CMD_TRANSPORT_FUNCTION, CMD_FFWD};
                sendBytes(buffer);
                rec_on = false;
                IlluminateButton(mFfwdButton);
                Log.d(TAG, ">> CMD_FFWD");
            }
        });
        mStopButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                byte[] buffer = {CMD_TRANSPORT_FUNCTION, CMD_STOP};
                sendBytes(buffer);
                rec_on = false;
                IlluminateButton(mStopButton);
                Log.d(TAG, ">> CMD_STOP");
            }
        });
        mPauseButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                byte[] buffer = {CMD_TRANSPORT_FUNCTION, CMD_PAUSE};
                sendBytes(buffer);
                IlluminateButton(mPauseButton);
                Log.d(TAG, ">> CMD_PAUSE");
            }
        });
        mAutoMuteButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                byte[] buffer = {CMD_TRANSPORT_FUNCTION, CMD_AUTO_MUTE};
                sendBytes(buffer);
                IlluminateButton(mAutoMuteButton);
                Log.d(TAG, ">> CMD_AUTO_MUTE");
                }
        });
        Log.d(TAG, "...done");

        // Initialise the BluetoothService to perform bluetooth connections
        Log.d(TAG, "Initialise Bluetooth Service...");
        mBTService = new BluetoothService(activity, mHandler);

    }
    //-------------------------------------------------------------------------
    /**
     * Sets the selected button to "illuminated" state.
     *
     * @param button Button to illuminate.
     */
    private void IlluminateButton(ImageButton button) {
        // Set all buttons to "off"
        mRecButton.setImageResource(R.drawable.btn_single_rec);
        mRewButton.setImageResource(R.drawable.btn_single_rew);
        mPlayButton.setImageResource(R.drawable.btn_double_play);
        mFfwdButton.setImageResource(R.drawable.btn_single_ffwd);
        mStopButton.setImageResource(R.drawable.btn_double_stop);
        mPauseButton.setImageResource(R.drawable.btn_single_pause);
        mAutoMuteButton.setImageResource(R.drawable.btn_single_auto_mute);
        mPlayFwdButton.setImageResource(R.drawable.btn_single_play_fwd);
        mPlayRevButton.setImageResource(R.drawable.btn_single_play_rev);

        if(rec_on) {
            mRecButton.setImageResource(R.drawable.btn_single_rec_on);
            mAutoMuteButton.setImageResource(R.drawable.btn_single_auto_mute_on);
        }
        if(button == mRewButton) {
            mRewButton.setImageResource(R.drawable.btn_single_rew_on);
        }else if(button == mPlayButton) {
            mPlayButton.setImageResource(R.drawable.btn_double_play_on);
        }else if(button == mFfwdButton) {
            mFfwdButton.setImageResource(R.drawable.btn_single_ffwd_on);
        }else if(button == mStopButton) {
            mStopButton.setImageResource(R.drawable.btn_double_stop_on);
        }else if(button == mPauseButton) {
            mPauseButton.setImageResource(R.drawable.btn_single_pause_on);
        }else if(button == mAutoMuteButton) {
            if(rec_on) { // Only if rec_on is true
                mAutoMuteButton.setImageResource(R.drawable.btn_single_auto_mute_on);
            }
        }else if(button == mPlayFwdButton) {
            mPlayFwdButton.setImageResource(R.drawable.btn_single_play_fwd_on);
        }else if(button == mPlayRevButton) {
            mPlayRevButton.setImageResource(R.drawable.btn_single_play_rev_on);
        }
    }
    /**
     * Sends an array of bytes to connected device.
     *
     * @param message An array of bytes to send.
     */
    private void sendBytes(byte[] message) {
        // Check that we're actually connected before trying anything
        if (mBTService.getState() != BluetoothService.STATE_CONNECTED) {
            Toast.makeText(getActivity(), R.string.not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        mBTService.write(message);
    }

    /**
     * Sends the initial setup to the connected receiver.
     * Also sends STOP command to sync state-machine in the receiver.
     *
     */
    private void setupReceiver() {
        FragmentActivity activity = getActivity();
        if (null == activity) {
            return;
        }
        byte[] buffer = {CMD_SET_REC_STDBY_MODE, RecStdbyMode,
                CMD_SET_DELAY, delay, CMD_SET_LED_PWM, pwm, CMD_TRANSPORT_FUNCTION, CMD_STOP};
        sendBytes(buffer);

        rec_on = false;
        IlluminateButton(mStopButton);
    }

    /**
     * Updates the status on the action bar.
     *
     * @param resId a string resource ID
     */
    private void setStatus(int resId) {
        FragmentActivity activity = getActivity();
        if (null == activity) {
            return;
        }
        final ActionBar actionBar = activity.getActionBar();
        if (null == actionBar) {
            return;
        }
        actionBar.setSubtitle(resId);
    }

    /**
     * Updates the status on the action bar.
     *
     * @param subTitle status
     */
    private void setStatus(CharSequence subTitle) {
        FragmentActivity activity = getActivity();
        if (null == activity) {
            return;
        }
        final ActionBar actionBar = activity.getActionBar();
        if (null == actionBar) {
            return;
        }
        actionBar.setSubtitle(subTitle);
    }

    /**
     * The Handler that gets information back from the BluetoothService
     */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            FragmentActivity activity = getActivity();
            switch (msg.what) {
                case Constants.MESSAGE_STATE_CHANGE:
                    Log.d(TAG, "Handler: MESSAGE_STATE_CHANGE");
                    switch (msg.arg1) {
                        case BluetoothService.STATE_CONNECTED:
                            setupReceiver();
                            setStatus(getString(R.string.title_connected_to, mConnectedDeviceName));
                            if (menu_icon_bt != null) {
                                menu_icon_bt.setIcon(getResources().getDrawable(R.drawable.ic_bluetooth_connected_48));
                            }
                            Log.d(TAG, "MESSAGE_STATE_CHANGE->STATE_CONNECTED");
                            break;
                        case BluetoothService.STATE_CONNECTING:
                            setStatus(R.string.title_connecting);
                            Log.d(TAG, "MESSAGE_STATE_CHANGE->STATE_CONNECTING");
                            break;
                        case BluetoothService.STATE_LISTEN:
                        case BluetoothService.STATE_NONE:
                            setStatus(R.string.title_not_connected);
                            if (menu_icon_bt != null) {
                                menu_icon_bt.setIcon(getResources().getDrawable(R.drawable.ic_bluetooth_48));
                            }
                            Log.d(TAG, "MESSAGE_STATE_CHANGE->STATE_LISTEN | STATE_NONE");
                            break;
                    }
                    break;
                case Constants.MESSAGE_WRITE:
                    //byte[] writeBuf = (byte[]) msg.obj;
                    // construct a string from the buffer
                    //String writeMessage = new String(writeBuf);
                    //mConversationArrayAdapter.add("Me:  " + writeMessage);
                    Log.d(TAG, "Handler: MESSAGE_WRITE");
                    break;
                case Constants.MESSAGE_READ:
                    //byte[] readBuf = (byte[]) msg.obj;
                    // construct a string from the valid bytes in the buffer
                    //String readMessage = new String(readBuf, 0, msg.arg1);
                    //mConversationArrayAdapter.add(mConnectedDeviceName + ":  " + readMessage);
                    Log.d(TAG, "Handler: MESSAGE_READ");
                    break;
                case Constants.MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    mConnectedDeviceName = msg.getData().getString(Constants.DEVICE_NAME);
                    if (null != activity) {
                        Toast.makeText(activity, "Connected to "
                                + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                    }
                    Log.d(TAG, "Handler: MESSAGE_DEVICE_NAME");
                    break;
                case Constants.MESSAGE_TOAST:
                    if (null != activity) {
                        Toast.makeText(activity, msg.getData().getString(Constants.TOAST),
                                Toast.LENGTH_SHORT).show();
                    }
                    Log.d(TAG, "Handler: MESSAGE_TOAST");
                    break;
            }
        }
    };

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE_SECURE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data, true);
                }
                break;
            case REQUEST_CONNECT_DEVICE_INSECURE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data, false);
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    // Bluetooth is now enabled, so set up a bluetooth session
                    setupBT_Remote();
                } else {
                    // User did not enable Bluetooth or an error occurred
                    Log.d(TAG, "BT not enabled");
                    FragmentActivity activity = getActivity();
                    if (activity != null) {
                        Toast.makeText(activity, R.string.bt_not_enabled_leaving,
                                Toast.LENGTH_SHORT).show();
                        activity.finish();
                    }
                }
        }
    }

    /**
     * Establish connection with other device
     *
     * @param data   An {@link Intent} with {@link DeviceListActivity#EXTRA_DEVICE_ADDRESS} extra.
     * @param secure Socket Security type - Secure (true) , Insecure (false)
     */
    private void connectDevice(Intent data, boolean secure) {
        // Get the device MAC address
        Bundle extras = data.getExtras();
        if (extras == null) {
            return;
        }
        String address = extras.getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
        // Get the BluetoothDevice object
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        // Attempt to connect to the device
        mBTService.connect(device, secure);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.bluetooth_remote, menu);
        menu_icon_bt = menu.findItem(R.id.secure_connect_scan);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case R.id.secure_connect_scan: {
                menu_icon_bt.setIcon(getResources().getDrawable(R.drawable.ic_bluetooth_48));
                // Launch the DeviceListActivity to see devices and do scan
                Intent serverIntent = new Intent(getActivity(), DeviceListActivity.class);
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_SECURE);
                return true;
            }
            case R.id.insecure_connect_scan: {
                menu_icon_bt.setIcon(getResources().getDrawable(R.drawable.ic_bluetooth_48));
                // Launch the DeviceListActivity to see devices and do scan
                Intent serverIntent = new Intent(getActivity(), DeviceListActivity.class);
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_INSECURE);
                return true;
            }
            case R.id.menu_settings:
                final Intent intent = new Intent(getActivity(), SettingsActivity.class);
                startActivity(intent);
                return true;

            default:
                return super.onOptionsItemSelected(item);

        }
    }

}
