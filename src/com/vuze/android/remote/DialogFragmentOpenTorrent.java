package com.vuze.android.remote;

import android.app.Activity;
import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.view.View;
import android.widget.EditText;

import com.vuze.android.remote.AndroidUtils.AlertDialogBuilder;

public class DialogFragmentOpenTorrent
	extends DialogFragment
{

	public interface OpenTorrentDialogListener
	{
		public void openTorrent(String s);
	}

	private OpenTorrentDialogListener mListener;

	private EditText mTextTorrent;

	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {

		AlertDialogBuilder alertDialogBuilder = AndroidUtils.createAlertDialogBuilder(
				getActivity(), R.layout.dialog_open_torrent);

		View view = alertDialogBuilder.view;
		Builder builder = alertDialogBuilder.builder;

		mTextTorrent = (EditText) view.findViewById(R.id.addtorrent_tb);

		// Add action buttons
		builder.setPositiveButton(android.R.string.ok,
				new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int id) {
						if (mListener != null) {
							mListener.openTorrent(mTextTorrent.getText().toString());
						}
					}
				});
		builder.setNegativeButton(android.R.string.cancel,
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int id) {
						DialogFragmentOpenTorrent.this.getDialog().cancel();
					}
				});
		return builder.create();
	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);

		if (activity instanceof OpenTorrentDialogListener) {
			mListener = (OpenTorrentDialogListener) activity;
		}
	}
}
