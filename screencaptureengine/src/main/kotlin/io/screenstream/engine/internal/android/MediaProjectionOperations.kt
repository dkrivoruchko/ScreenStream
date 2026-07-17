package io.screenstream.engine.internal.android

import android.media.projection.MediaProjection
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnedOwner

internal object AndroidProjectionCallbackRegistrationReceipt : OperationReceipt

internal class AndroidProjectionCallbackRegistrationEvidence : OperationEvidence {
    override val receipt: AndroidProjectionCallbackRegistrationReceipt =
        AndroidProjectionCallbackRegistrationReceipt
    override val returnedOwner: OperationReturnedOwner? = null
}

internal class AndroidProjectionCallbackRegistrationOwnerBag(
    internal val projection: MediaProjection,
    internal val callback: MediaProjection.Callback,
) : OperationOwnerBag


internal object AndroidProjectionCallbackUnregistrationReceipt : OperationReceipt

internal class AndroidProjectionCallbackUnregistrationEvidence : OperationEvidence {
    override val receipt: AndroidProjectionCallbackUnregistrationReceipt =
        AndroidProjectionCallbackUnregistrationReceipt
    override val returnedOwner: OperationReturnedOwner? = null
}

internal class AndroidProjectionCallbackUnregistrationOwnerBag(
    internal val projection: MediaProjection,
    internal val callback: MediaProjection.Callback,
) : OperationOwnerBag


internal object AndroidProjectionStopReceipt : OperationReceipt

internal class AndroidProjectionStopEvidence : OperationEvidence {
    override val receipt: AndroidProjectionStopReceipt = AndroidProjectionStopReceipt
    override val returnedOwner: OperationReturnedOwner? = null
}

internal class AndroidProjectionStopOwnerBag(
    internal val projection: MediaProjection,
) : OperationOwnerBag
