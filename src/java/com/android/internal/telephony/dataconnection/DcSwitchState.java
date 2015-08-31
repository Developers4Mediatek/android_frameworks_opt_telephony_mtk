/*
* Copyright (C) 2011-2014 MediaTek Inc.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.internal.telephony.dataconnection;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import com.android.internal.telephony.dataconnection.DctController.RequestInfo;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneProxy;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.telephony.Rlog;
import android.text.TextUtils;

import android.util.Log;

public class DcSwitchState extends StateMachine {
    private static final boolean DBG = true;
    private static final boolean VDBG = false;
    private static final String LOG_TAG = "DcSwitchState";

    // ***** Event codes for driving the state machine
    private static final int BASE = Protocol.BASE_DATA_CONNECTION_TRACKER + 0x00001000;
    private static final int EVENT_CONNECTED = BASE + 0;
    private static final int EVENT_DATA_DETACH_DONE = BASE + 1;

    private int mId;
    private Phone mPhone;
    private AsyncChannel mAc;

    private IdleState mIdleState = new IdleState();
    private AttachingState mAttachingState = new AttachingState();
    private AttachedState mAttachedState = new AttachedState();
    private DetachingState mDetachingState = new DetachingState();
    private DefaultState mDefaultState = new DefaultState();

    //MTK START
    private boolean mNeedAttach = true;
    public static final String DCSTATE_IDLE = "idle";
    public static final String DCSTATE_ATTACHING = "attaching";
    public static final String DCSTATE_ATTACHED = "attached";
    public static final String DCSTATE_DETACHING = "detaching";
    //MTK END

    protected DcSwitchState(Phone phone, String name, int id) {
        super(name);
        if (DBG) log("DcSwitchState constructor E");
        mPhone = phone;
        mId = id;

        addState(mDefaultState);
        addState(mIdleState, mDefaultState);
        addState(mAttachingState, mDefaultState);
        addState(mAttachedState, mDefaultState);
        addState(mDetachingState, mDefaultState);
        setInitialState(mIdleState);
        if (DBG) log("DcSwitchState constructor X");
    }

    public void notifyDataConnection(int phoneId, String state, String reason,
            String apnName, String apnType, boolean unavailable) {
        if (phoneId == mId &&
                TextUtils.equals(state, PhoneConstants.DataState.CONNECTED.toString())) {
            sendMessage(obtainMessage(EVENT_CONNECTED));
        }
    }

    private class IdleState extends State {
        @Override
        public void enter() {
            if (DBG) log("IdleState: enter");

            try {
                DctController.getInstance().processRequests();
                DctController.getInstance().notifyDcSwitchStateChange(DCSTATE_IDLE);
            } catch (RuntimeException e) {
                if (DBG) loge("DctController is not ready");
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcSwitchAsyncChannel.REQ_CONNECT: {
                    if (DBG) {
                        log("IdleState: REQ_CONNECT");
                    }

                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_CONNECT,
                            PhoneConstants.APN_REQUEST_STARTED);

                    transitionTo(mAttachingState);
                    retVal = HANDLED;
                    break;
                }

                case EVENT_CONNECTED: {
                    if (DBG) {
                        log("IdleState: Receive invalid event EVENT_CONNECTED!");
                    }
                    retVal = HANDLED;
                    break;
                }
                case DcSwitchAsyncChannel.REQ_DISCONNECT_ALL: {
                    if (DBG) {
                        log("IdleState: REQ_DISCONNECT_ALL, shouldn't in this state," +
                            "but regards it had disconnected");
                    }
                    mAc.replyToMessage(msg,
                            DcSwitchAsyncChannel.REQ_DISCONNECT_ALL,
                            PhoneConstants.APN_ALREADY_INACTIVE);
                    retVal = HANDLED;
                    break;
                }
                default:
                    if (VDBG) {
                        log("IdleState: nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private class AttachingState extends State {
        @Override
        public void enter() {
            log("AttachingState: enter");
            if (mNeedAttach) {
                PhoneBase pb = (PhoneBase) ((PhoneProxy) mPhone).getActivePhone();
                pb.mCi.setDataAllowed(true, null);
            } else {
                log("AttachingState: caused by lost connection, don't attach again");
                mNeedAttach = true;
            }
            DctController.getInstance().notifyDcSwitchStateChange(DCSTATE_ATTACHING);
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcSwitchAsyncChannel.REQ_CONNECT: {
                    if (DBG) {
                        log("AttachingState: REQ_CONNECT");
                    }

                    PhoneBase pb = (PhoneBase) ((PhoneProxy) mPhone).getActivePhone();
                    pb.mCi.setDataAllowed(true, null);

                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_CONNECT,
                            PhoneConstants.APN_REQUEST_STARTED);
                    retVal = HANDLED;
                    break;
                }

                case DcSwitchAsyncChannel.EVENT_DATA_ATTACHED:
                    if (DBG) {
                        log("AttachingState: EVENT_DATA_ATTACHED");
                    }
                    transitionTo(mAttachedState);
                    retVal = HANDLED;
                    break;

                case DcSwitchAsyncChannel.REQ_DISCONNECT_ALL: {
                    if (DBG) {
                        log("AttachingState: REQ_DISCONNECT_ALL");
                    }
                    DctController.getInstance().releaseAllRequests(mId);
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_DISCONNECT_ALL,
                            PhoneConstants.APN_REQUEST_STARTED);

                    transitionTo(mDetachingState);
                    retVal = HANDLED;
                    break;
                }

                default:
                    if (VDBG) {
                        log("AttachingState: nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private class AttachedState extends State {
        @Override
        public void enter() {
            if (DBG) log("AttachedState: enter");
            //When enter attached state, we need exeute all requests.
            DctController.getInstance().executeAllRequests(mId);
            DctController.getInstance().notifyDcSwitchStateChange(DCSTATE_ATTACHED);
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcSwitchAsyncChannel.REQ_CONNECT: {
                    RequestInfo apnRequest = null;

                    if (msg.obj != null) {
                        apnRequest = (RequestInfo) msg.obj;
                        DctController.getInstance().executeRequest(apnRequest);
                    }

                    if (DBG) {
                        log("AttachedState: REQ_CONNECT, apnRequest=" + apnRequest);
                    }

                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_CONNECT,
                            PhoneConstants.APN_REQUEST_STARTED);
                    retVal = HANDLED;
                    break;
                }
                case DcSwitchAsyncChannel.REQ_DISCONNECT: {
                    RequestInfo apnRequest = (RequestInfo) msg.obj;
                    if (DBG) {
                        log("AttachedState: REQ_DISCONNECT apnRequest=" + apnRequest);
                    }

                    DctController.getInstance().releaseRequest(apnRequest);
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_CONNECT,
                            PhoneConstants.APN_REQUEST_STARTED);

                    retVal = HANDLED;
                    break;
                }

                case DcSwitchAsyncChannel.REQ_DISCONNECT_ALL: {
                    if (DBG) {
                        log("AttachedState: REQ_DISCONNECT_ALL");
                    }
                    DctController.getInstance().releaseAllRequests(mId);
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_DISCONNECT_ALL,
                            PhoneConstants.APN_REQUEST_STARTED);

                    transitionTo(mDetachingState);
                    retVal = HANDLED;
                    break;
                }

                case DcSwitchAsyncChannel.EVENT_DATA_DETACHED: {
                    if (DBG) {
                        log("AttachedState: EVENT_DATA_DETACHED");
                    }
                    mNeedAttach = false;
                    transitionTo(mAttachingState);
                    retVal = HANDLED;
                    break;
                }

                default:
                    if (VDBG) {
                        log("AttachedState: nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private class DetachingState extends State {
        @Override
        public void enter() {
            if (DBG) log("DetachingState: enter");
            PhoneBase pb = (PhoneBase) ((PhoneProxy) mPhone).getActivePhone();
            pb.mCi.setDataAllowed(false, obtainMessage(EVENT_DATA_DETACH_DONE));
            DctController.getInstance().notifyDcSwitchStateChange(DCSTATE_DETACHING);
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcSwitchAsyncChannel.REQ_CONNECT: {
                    if (DBG) {
                        log("DetachingState: REQ_CONNECT, return fail");
                    }
                    PhoneBase pb = (PhoneBase) ((PhoneProxy) mPhone).getActivePhone();
                    pb.mCi.setDataAllowed(false, obtainMessage(
                            DcSwitchAsyncChannel.EVENT_DATA_DETACHED));
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_CONNECT,
                            PhoneConstants.APN_REQUEST_FAILED);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DATA_DETACH_DONE: {
                    if (DBG) {
                        log("DeattachingState: EVENT_DATA_DETACH_DONE");
                    }
                    transitionTo(mIdleState);
                    retVal = HANDLED;
                    break;
                }
                case DcSwitchAsyncChannel.REQ_DISCONNECT_ALL: {
                    if (DBG) {
                        log("DeattachingState: REQ_DISCONNECT_ALL, already deacting.");
                    }
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_DISCONNECT_ALL,
                            PhoneConstants.APN_REQUEST_STARTED);
                    retVal = HANDLED;
                    break;
                }
                default:
                    if (VDBG) {
                        log("DeattachingState: nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private class DefaultState extends State {
        @Override
        public boolean processMessage(Message msg) {
            AsyncResult ar;

            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                    if (mAc != null) {
                        if (VDBG) log("Disconnecting to previous connection mAc=" + mAc);
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_FULL_CONNECTION_REFUSED_ALREADY_CONNECTED);
                    } else {
                        mAc = new AsyncChannel();
                        mAc.connected(null, getHandler(), msg.replyTo);
                        if (VDBG) log("DcDefaultState: FULL_CONNECTION reply connected");
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_SUCCESSFUL, mId, "hi");
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECT: {
                    if (VDBG) log("CMD_CHANNEL_DISCONNECT");
                    mAc.disconnect();
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    if (VDBG) log("CMD_CHANNEL_DISCONNECTED");
                    mAc = null;
                    break;
                }
                case DcSwitchAsyncChannel.REQ_IS_IDLE_STATE: {
                    boolean val = getCurrentState() == mIdleState;
                    if (VDBG) log("REQ_IS_IDLE_STATE  isIdle=" + val);
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_IS_IDLE_STATE, val ? 1 : 0);
                    break;
                }
                case DcSwitchAsyncChannel.REQ_IS_IDLE_OR_DETACHING_STATE: {
                    boolean val = (getCurrentState() == mIdleState ||
                            getCurrentState() == mDetachingState);
                    if (VDBG) log("REQ_IS_IDLE_OR_DETACHING_STATE  isIdleDeacting=" + val);
                    mAc.replyToMessage(msg,
                            DcSwitchAsyncChannel.RSP_IS_IDLE_OR_DETACHING_STATE, val ? 1 : 0);
                    break;
                }
                default:
                    if (DBG) {
                        log("DefaultState: shouldn't happen but ignore msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    break;
            }
            return HANDLED;
        }
    }

    protected void log(String s) {
        Rlog.d(LOG_TAG, "[" + getName() + "] " + s);
    }
}
