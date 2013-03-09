package com.outsource.ecg.app;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;

import org.afree.data.xy.XYSeries;
import com.outsource.ecg.ui.ECGUserAdapter;
import com.outsource.ecg.ui.JDBCXYChartView;
import com.outsource.ecg.defs.ECGUser;
import com.outsource.ecg.defs.ECGUserManager;
import com.outsource.ecg.defs.ECGUtils;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.outsource.ecg.R;

public class EcgClientActivity extends Activity {
	private static final String TAG = "EcgClient";
	private static final boolean DEBUG = true;
	private static final boolean TEST_USER_RECORDS = false;

	private static String DB_FILE_NAME = "ecg.sqlite";
	private String mDBFilePath;
	private Object listener;
	private XYSeries mDefaultSeries;
	private BluetoothAdapter mBluetoothAdapter;
	// Name of the connected device
	private String mConnectedDeviceName = null;

	// Intent request codes
	private static final int REQUEST_USER_MANAGE = 0;
	private static final int REQUEST_CONNECT_DEVICE_SECURE = 1;
	private static final int REQUEST_CONNECT_DEVICE_INSECURE = 2;
	private static final int REQUEST_ENABLE_BT = 3;
	private static final int REQUEST_SELECT_HISTROY_ECG_RECORD = 4;

	// Message types sent from the EcgService Handler
	public static final int MESSAGE_STATE_CHANGE = 1;
	public static final int MESSAGE_READ = 2;
	public static final int MESSAGE_WRITE = 3;
	public static final int MESSAGE_DEVICE_NAME = 4;
	public static final int MESSAGE_TOAST = 5;

	// Key names received from the EcgService Handler
	public static final String DEVICE_NAME = "device_name";
	public static final String TOAST = "toast";
	private TextView mNameText;
	private TextView mIDText;
	private TextView mHBRText;
	private TextView mConnectionText;
	private JDBCXYChartView mPlotView;
	boolean mStarted = false;

	Button mStartStopBtn;
	// Member object for the ECG services
	private EcgService mEcgService = null;

	// The Handler that gets information back from the EcgService
	private final Handler mHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case MESSAGE_STATE_CHANGE:
				if (DEBUG)
					Log.i(TAG, "MESSAGE_STATE_CHANGE: " + msg.arg1);
				switch (msg.arg1) {
				case EcgService.STATE_CONNECTED:
					// setStatus(getString(R.string.title_connected_to,
					// mConnectedDeviceName));
					// mConversationArrayAdapter.clear();
				case EcgService.STATE_CONNECTING:
					// setStatus(R.string.title_connecting);
				case EcgService.STATE_LISTEN:
				case EcgService.STATE_NONE:
					// setStatus(R.string.title_not_connected);
					updateConnectionInfo();
				}
				break;
			case MESSAGE_WRITE:
				byte[] writeBuf = (byte[]) msg.obj;
				// construct a string from the buffer
				String writeMessage = new String(writeBuf);
				// mConversationArrayAdapter.add("Me:  " + writeMessage);
				break;
			case MESSAGE_READ:
				byte[] readBuf = (byte[]) msg.obj;
				// construct a string from the valid bytes in the buffer
				String readMessage = new String(readBuf, 0, msg.arg1);
				// mConversationArrayAdapter.add(mConnectedDeviceName+":  " +
				// readMessage);
				break;
			case MESSAGE_DEVICE_NAME:
				// save the connected device's name
				mConnectedDeviceName = msg.getData().getString(DEVICE_NAME);
				Toast.makeText(getApplicationContext(),
						"Connected to " + mConnectedDeviceName,
						Toast.LENGTH_SHORT).show();
				break;
			case MESSAGE_TOAST:
				Toast.makeText(getApplicationContext(),
						msg.getData().getString(TOAST), Toast.LENGTH_SHORT)
						.show();
				break;
			}
		}
	};

	// External storage state listener
	private final BroadcastReceiver mSdcardListener = new BroadcastReceiver() {

		@Override
		public void onReceive(Context context, Intent intent) {

			String action = intent.getAction();
			Log.d("TAG", "sdcard action:::::" + action);
			if (Intent.ACTION_MEDIA_MOUNTED.equals(action)
					|| Intent.ACTION_MEDIA_SCANNER_STARTED.equals(action)
					|| Intent.ACTION_MEDIA_SCANNER_FINISHED.equals(action)) {
				// mounted
				try {
					ECGUserManager.Instance().loadUserInfo(true);
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					Toast.makeText(context,
							"ECGUserManager.Instance() failed!",
							Toast.LENGTH_LONG).show();
					finish();
				}
			} else if (Intent.ACTION_MEDIA_REMOVED.equals(action)
					|| Intent.ACTION_MEDIA_UNMOUNTED.equals(action)
					|| Intent.ACTION_MEDIA_BAD_REMOVAL.equals(action)) {
				// fail to mounted, do nothing
				Toast.makeText(context, "External storage mount failed!",
						Toast.LENGTH_LONG).show();
			}

		}
	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.ecg_client_db);
		mPlotView = (JDBCXYChartView) findViewById(R.id.ecg_chart);
		updatePlotView();
		mNameText = (TextView) findViewById(R.id.patient_name);
		mIDText = (TextView) findViewById(R.id.patient_id);
		mHBRText = (TextView) findViewById(R.id.patient_hbr);
		mConnectionText = (TextView) findViewById(R.id.connection_status);
		/*
		 * setContentView(R.layout.ecg_client_main); XYPlotView plotView =
		 * (XYPlotView) findViewById(R.id.ecg_chart); XYSeriesCollection series
		 * = plotView.getDataset(); mDefaultSeries = (XYSeries)
		 * series.getSeries().get( XYPlotView.DEFAULT_SERIES_INDEX);
		 * 
		 * for (int i = 1; i <= 100; i++) { mDefaultSeries.add(i * 1.0,
		 * Math.random() * 7000 + 11000); }
		 */

		// Get local Bluetooth adapter
		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

		// If the adapter is null, then Bluetooth is not supported
		if (mBluetoothAdapter == null) {
			Toast.makeText(this, "Bluetooth is not available",
					Toast.LENGTH_LONG).show();
			finish();
			return;
		}

		// register external storage state listener
		IntentFilter intentFilter = new IntentFilter(
				Intent.ACTION_MEDIA_MOUNTED);
		intentFilter.addAction(Intent.ACTION_MEDIA_SCANNER_STARTED);
		intentFilter.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
		intentFilter.addAction(Intent.ACTION_MEDIA_REMOVED);
		intentFilter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
		intentFilter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
		intentFilter.addDataScheme("file");
		registerReceiver(mSdcardListener, intentFilter);
		mStartStopBtn = (Button) findViewById(R.id.start_stop_btn);
		mStartStopBtn.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View v) {
				// TODO Auto-generated method stub
				// first need select a valid ECG User
				if (!ECGUserManager.getCurrentUser().isValid()) {
					Toast.makeText(EcgClientActivity.this,
							"Please select a valid ecg user!",
							Toast.LENGTH_LONG).show();
					return;
				}

				// second need select a valid ECG User
				if (EcgService.STATE_CONNECTED != EcgClientActivity.this.mEcgService
						.getState()) {
					Toast.makeText(EcgClientActivity.this,
							"Target device is not connected!",
							Toast.LENGTH_LONG).show();
					return;
				}

				synchronized (EcgClientActivity.this) {
					mStarted = !mStarted;
					if (mStarted) {
						mStartStopBtn.setText(R.string.stop_label);
						// do the real start stuff
					} else {
						mStartStopBtn.setText(R.string.start_label);
						// do the real stop stuff
					}
				}
			}
		});

		Button loadBtn = (Button) findViewById(R.id.load_btn);
		loadBtn.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View v) {
				// TODO Auto-generated method stub
				if (!ECGUserManager.getCurrentUser().isValid()) {
					Toast.makeText(EcgClientActivity.this,
							"Please select a valid ecg user!",
							Toast.LENGTH_LONG).show();
					return;
				} else {
					startActivityForResult(new Intent(EcgClientActivity.this,
							ECGUserHistroyRecordActivity.class),
							REQUEST_SELECT_HISTROY_ECG_RECORD);
				}

			}
		});
	}

	@Override
	protected void onStart() {
		// TODO Auto-generated method stub
		super.onStart();
		enableBluetoothFunction();
		checkExternalStorageMounted();
	}

	@Override
	protected void onResume() {
		// TODO Auto-generated method stub
		super.onResume();
		Log.d(TAG, "onResume() in");
		if (null != mEcgService) {
			mEcgService.start();
		}
		updateCurrentUserInfo();
		updateConnectionInfo();
	}

	private void updateConnectionInfo() {
		// TODO Auto-generated method stub
		switch (mEcgService.getState()) {
		case EcgService.STATE_CONNECTED:
			mConnectionText.setText(R.string.connected);
			break;
		case EcgService.STATE_CONNECTING:
			mConnectionText.setText(R.string.connecting);
			break;
		case EcgService.STATE_LISTEN:
			mConnectionText.setText(R.string.listen);
			break;
		case EcgService.STATE_NONE:
		default:
			mConnectionText.setText(R.string.none);
			break;
		}
	}

	private void updatePlotView() {
		if (DEBUG) {
			mDBFilePath = Environment.getExternalStorageDirectory() + "/ecg/"
					+ DB_FILE_NAME;
			// JDBCXYChartView contentView = new JDBCXYChartView(this,
			// mDBFilePath);
		} else {
			mDBFilePath = ECGUserManager.getCurrentUserDataPath();
			Log.d(TAG, "current user's mDBFilePath:" + mDBFilePath);

		}
		Log.d(TAG, "current user's mDBFilePath:" + mDBFilePath);
		mPlotView.setDBPath(mDBFilePath, "XYData1"/* test table */);
	}

	private void updateCurrentUserInfo() {
		OnClickListener clickListener = new View.OnClickListener() {

			@Override
			public void onClick(View v) {
				EcgClientActivity.this.startActivityForResult(new Intent(
						ECGUtils.ACTION_ECG_USER_MANAGE),
						EcgClientActivity.REQUEST_USER_MANAGE);
			}
		};

		ECGUser currentUser = ECGUserManager.getCurrentUser();
		Log.d(TAG,
				"currentUser:" + currentUser + " valid:"
						+ currentUser.isValid() + " name:"
						+ currentUser.getName());
		if (null != mNameText) {
			mNameText.setText("Name:" + currentUser.getName());
			mNameText.setOnClickListener(clickListener);
		}
		if (null != mIDText) {
			mIDText.setText("ID:\n" + String.valueOf(currentUser.getID()));
			mIDText.setOnClickListener(clickListener);
		}
		if (null != mHBRText) {
			mHBRText.setText("HBR:\n" + String.valueOf(currentUser.getHBR()));
			mHBRText.setOnClickListener(clickListener);
		}
		if (!currentUser.isValid()) {
			Toast.makeText(
					this,
					"Current user is invalid, Please press to set user information!",
					Toast.LENGTH_LONG).show();
		}

	}

	@Override
	protected void onDestroy() {
		// TODO Auto-generated method stub
		super.onDestroy();
		unregisterReceiver(mSdcardListener);
	}

	private void setUpEcgService() {
		// Initialize the BluetoothChatService to perform bluetooth connections
		if (null == mEcgService) {
			mEcgService = new EcgService(this, mHandler);
		}
	}

	private void ensureDiscoverable() {
		if (DEBUG)
			Log.d(TAG, "ensure discoverable");
		if (mBluetoothAdapter.getScanMode() != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
			Intent discoverableIntent = new Intent(
					BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
			discoverableIntent.putExtra(
					BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
			startActivity(discoverableIntent);
		}
	}

	private void enableBluetoothFunction() {
		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		if (!mBluetoothAdapter.isEnabled()) {
			Intent enableBtIntent = new Intent(
					BluetoothAdapter.ACTION_REQUEST_ENABLE);
			startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
		} else {
			setUpEcgService();
		}

	}

	private void checkExternalStorageMounted() {
		Log.d(TAG,
				"Result: "
						+ (Environment.MEDIA_MOUNTED != Environment
								.getExternalStorageState()));
		if (!Environment.getExternalStorageState().equals(
				Environment.MEDIA_MOUNTED)) {
			Toast.makeText(this,
					getString(R.string.external_storage_unmounted_prompt),
					Toast.LENGTH_LONG).show();
			finish();
		} else {
			// populate user infomation from database
			try {
				ECGUserManager.Instance().loadUserInfo(true);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				Toast.makeText(this, "ECGUserManager.Instance() failed!",
						Toast.LENGTH_LONG).show();
				finish();
			}
		}
	}

	private void connectDevice(Intent data, boolean secure) {
		// Get the device MAC address
		if (null != data && null != data.getExtras()) {
			String address = data.getExtras().getString(
					DeviceListActivity.EXTRA_DEVICE_ADDRESS);
			// Get the BluetoothDevice object
			BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
			// Attempt to connect to the device
			mEcgService.connect(device, secure);
		} else {
			Log.e(TAG,
					"FATAL ERROR! The selected device for connect has no address!");
		}

	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.main_option_menu, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		Intent serverIntent = null;
		switch (item.getItemId()) {
		case R.id.secure_connect_scan:
			// Launch the DeviceListActivity to see devices and do scan
			serverIntent = new Intent(this, DeviceListActivity.class);
			startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_SECURE);
			return true;
		case R.id.insecure_connect_scan:
			// Launch the DeviceListActivity to see devices and do scan
			serverIntent = new Intent(this, DeviceListActivity.class);
			startActivityForResult(serverIntent,
					REQUEST_CONNECT_DEVICE_INSECURE);
			return true;
		case R.id.discoverable:
			// Ensure this device is discoverable by others
			ensureDiscoverable();
			return true;
		}
		return false;
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		// TODO Auto-generated method stub
		if (DEBUG)
			Log.d(TAG, "onActivityResult " + resultCode);

		super.onActivityResult(requestCode, resultCode, data);
		switch (requestCode) {
		case REQUEST_ENABLE_BT:
			// When the request to enable Bluetooth returns
			if (resultCode == Activity.RESULT_OK) {
				// Bluetooth is now enabled, so set up ECG Service
				setUpEcgService();
			} else {
				// User did not enable Bluetooth or an error occurred
				Log.d(TAG, "BT not enabled");
				Toast.makeText(this, R.string.bt_not_enabled_leaving,
						Toast.LENGTH_SHORT).show();
				finish();
			}
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
		case REQUEST_USER_MANAGE:
			if (resultCode == Activity.RESULT_OK) {
				ECGUser user = data
						.getParcelableExtra(ECGUserAdapter.CURRENT_USER_EXTRA);
				updateCurrentUserInfo();
				Toast.makeText(this,
						"UserManage activity returned user:" + user,
						Toast.LENGTH_LONG).show();

				if (TEST_USER_RECORDS) {
					Toast.makeText(this, "TEST_USER_RECORDS START:",
							Toast.LENGTH_LONG).show();

					ArrayList<Double> series = new ArrayList<Double>();
					series.add(4.10);
					series.add(4.12);
					series.add(4.16);
					series.add(4.19);
					series.add(4.20);
					series.add(4.22);
					series.add(4.29);

					try {
						Connection connection = ECGUtils
								.getConnection(ECGUserManager
										.getCurrentUserDataPath());
						ECGUserManager.createUserHistroyRecord(connection,
								ECGUtils.createRecordTableFromDate(), series,
								0.1);
						for (String recordID : ECGUserManager
								.getUserHistroyRecords(connection,
										ECGUserManager.getCurrentUser())) {
							Log.d(TAG, "Record: " + recordID);
						}
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}

					Toast.makeText(this, "TEST_USER_RECORDS END:",
							Toast.LENGTH_LONG).show();
				}
			}
			break;

		case REQUEST_SELECT_HISTROY_ECG_RECORD:
			if (resultCode == Activity.RESULT_OK) {
				String recordID = data
						.getStringExtra(ECGUserHistroyRecordActivity.EXTRA_TARGET_RECORD_ID);
				Log.d(TAG, "The recordID selected is " + recordID);
				mPlotView.setDBPath(ECGUserManager.getCurrentUserDataPath(),
						recordID);
			}
			break;
		}

	}
}