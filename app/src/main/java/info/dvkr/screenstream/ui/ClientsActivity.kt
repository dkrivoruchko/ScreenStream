package info.dvkr.screenstream.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.util.Log
import android.view.LayoutInflater
import com.crashlytics.android.Crashlytics
import com.jakewharton.rxrelay.PublishRelay
import com.jjoe64.graphview.DefaultLabelFormatter
import com.jjoe64.graphview.GridLabelRenderer
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.R
import info.dvkr.screenstream.dagger.component.NonConfigurationComponent
import info.dvkr.screenstream.presenter.ClientsActivityPresenter
import kotlinx.android.synthetic.main.activity_clients.*
import kotlinx.android.synthetic.main.avtivity_clients_client_item.view.*
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import java.text.NumberFormat
import javax.inject.Inject


class ClientsActivity : BaseActivity(), ClientsActivityView {

    private val TAG = "ClientsActivity"

    companion object {
        fun getStartIntent(context: Context): Intent {
            return Intent(context, ClientsActivity::class.java)
        }
    }

    @Inject internal lateinit var presenter: ClientsActivityPresenter
    private val fromEvents = PublishRelay.create<ClientsActivityView.FromEvent>()
    private var lineGraphSeries: LineGraphSeries<DataPoint>? = null

    override fun fromEvent(): Observable<ClientsActivityView.FromEvent> = fromEvents.asObservable()

    override fun toEvent(toEvent: ClientsActivityView.ToEvent) {
        Observable.just(toEvent).observeOn(AndroidSchedulers.mainThread()).subscribe { event ->
            if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] toEvent: ${event.javaClass.simpleName}")

            when (event) {
                is ClientsActivityView.ToEvent.CurrentClients -> {
                    val clientsCount = event.clientsList.filter { !it.disconnected }.count()
                    textViewConnectedClients.text = getString(R.string.clients_activity_connected_clients).format(clientsCount)

                    linearLayoutConnectedClients.removeAllViews()
                    val layoutInflater = LayoutInflater.from(this)
                    event.clientsList.forEach {
                        with(layoutInflater.inflate(R.layout.avtivity_clients_client_item, null)) {
                            textViewClientItemAddress.text = context.getString(R.string.clients_activity_client) + it.clientAddress.toString().drop(1)
                            if (it.disconnected)
                                textViewClientItemAddress.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_client_disconnected_24dp, 0)
                            else if (it.hasBackpressure)
                                textViewClientItemAddress.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_client_slow_network_24dp, 0)
                            linearLayoutConnectedClients.addView(this)
                        }
                    }
                }

                is ClientsActivityView.ToEvent.TrafficHistory -> {
                    textViewCurrentTraffic.text = getString(R.string.clients_activity_current_traffic).format(toMbit(event.trafficHistory.last().bytes))

                    val arrayOfDataPoints = event.trafficHistory.map { DataPoint(it.time.toDouble(), toMbit(it.bytes)) }.sortedBy { it.x }.toTypedArray()
                    lineChartTraffic.removeAllSeries()
                    lineGraphSeries = LineGraphSeries(arrayOfDataPoints)
                    lineGraphSeries?.apply {
                        color = ContextCompat.getColor(this@ClientsActivity, R.color.colorAccent)
                        thickness = 6
                        lineChartTraffic.addSeries(lineGraphSeries)
                    }
                    lineChartTraffic.viewport.isXAxisBoundsManual = true
                    lineChartTraffic.viewport.setMinX(arrayOfDataPoints[0].x)
                    lineChartTraffic.viewport.setMaxX(arrayOfDataPoints[arrayOfDataPoints.size - 1].x)
                    lineChartTraffic.viewport.isYAxisBoundsManual = true
                    val maxY = Math.max(toMbit(event.maxY) * 1.2, 1.1)
                    lineChartTraffic.viewport.setMinY(maxY * -0.1)
                    lineChartTraffic.viewport.setMaxY(maxY)

                    val nf = NumberFormat.getInstance()
                    nf.minimumFractionDigits = 1
                    nf.maximumFractionDigits = 1
                    nf.minimumIntegerDigits = 1
                    lineChartTraffic.gridLabelRenderer.labelFormatter = DefaultLabelFormatter(nf, nf)
                    lineChartTraffic.gridLabelRenderer.verticalLabelsColor = ContextCompat.getColor(this, R.color.colorPrimaryText)
                    lineChartTraffic.gridLabelRenderer.isHorizontalLabelsVisible = false
                    lineChartTraffic.gridLabelRenderer.gridStyle = GridLabelRenderer.GridStyle.HORIZONTAL
                }

                is ClientsActivityView.ToEvent.TrafficPoint -> {
                    lineGraphSeries?.let {
                        val mbit = toMbit(event.trafficPoint.bytes)
                        textViewCurrentTraffic.text = getString(R.string.clients_activity_current_traffic).format(mbit)
                        it.appendData(DataPoint(event.trafficPoint.time.toDouble(), mbit), true, 60)
                        val maxY = Math.max(toMbit(event.maxY) * 1.2, 1.1)
                        lineChartTraffic.viewport.setMinY(maxY * -0.1)
                        lineChartTraffic.viewport.setMaxY(maxY)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: Start")
        Crashlytics.log(1, TAG, "onCreate: Start")
        setContentView(R.layout.activity_clients)

        textViewCurrentTraffic.text = getString(R.string.clients_activity_current_traffic).format(0.0)
        textViewConnectedClients.text = getString(R.string.clients_activity_connected_clients).format(0)

        presenter.attach(this)
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onCreate: End")
        Crashlytics.log(1, TAG, "onCreate: End")
    }

    override fun inject(injector: NonConfigurationComponent) = injector.inject(this)

    override fun onStart() {
        super.onStart()
        fromEvents.call(ClientsActivityView.FromEvent.TrafficHistoryRequest())
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onDestroy: Start")
        presenter.detach()
        if (BuildConfig.DEBUG_MODE) Log.w(TAG, "Thread [${Thread.currentThread().name}] onDestroy: End")
        Crashlytics.log(1, TAG, "onDestroy: End")
        super.onDestroy()
    }

    private fun toMbit(byte: Long) = (byte * 8).toDouble() / 1024 / 1024
}