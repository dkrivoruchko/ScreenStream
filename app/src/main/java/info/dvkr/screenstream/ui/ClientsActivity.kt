package info.dvkr.screenstream.ui

import android.arch.lifecycle.ViewModelProviders
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.view.LayoutInflater
import com.jakewharton.rxrelay.PublishRelay
import com.jjoe64.graphview.DefaultLabelFormatter
import com.jjoe64.graphview.GridLabelRenderer
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.presenter.PresenterFactory
import info.dvkr.screenstream.data.presenter.clients.ClientsPresenter
import info.dvkr.screenstream.data.presenter.clients.ClientsView
import kotlinx.android.synthetic.main.activity_clients.*
import kotlinx.android.synthetic.main.avtivity_clients_client_item.view.*
import org.koin.android.ext.android.inject
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import timber.log.Timber
import java.text.NumberFormat


class ClientsActivity : AppCompatActivity(), ClientsView {

    companion object {
        fun getStartIntent(context: Context): Intent = Intent(context, ClientsActivity::class.java)
    }

    private val presenterFactory: PresenterFactory by inject()
    private val presenter: ClientsPresenter by lazy {
        ViewModelProviders.of(this, presenterFactory).get(ClientsPresenter::class.java)
    }

    private val fromEvents = PublishRelay.create<ClientsView.FromEvent>()
    private var lineGraphSeries: LineGraphSeries<DataPoint>? = null

    override fun fromEvent(): Observable<ClientsView.FromEvent> = fromEvents.asObservable()

    override fun toEvent(toEvent: ClientsView.ToEvent) {
        Observable.just(toEvent).subscribeOn(AndroidSchedulers.mainThread()).subscribe { event ->
            Timber.d("[${Thread.currentThread().name} @${this.hashCode()}] toEvent: $event")

            when (event) {
                is ClientsView.ToEvent.CurrentClients -> {
                    val clientsCount = event.clientsList.filter { !it.disconnected }.count()
                    textViewConnectedClients.text = getString(R.string.clients_activity_connected_clients).format(clientsCount)

                    linearLayoutConnectedClients.removeAllViews()
                    val layoutInflater = LayoutInflater.from(this)
                    event.clientsList.forEach {
                        with(layoutInflater.inflate(R.layout.avtivity_clients_client_item, linearLayoutConnectedClients, false)) {
                            textViewClientItemAddress.text = context.getString(R.string.clients_activity_client) + it.clientAddress.toString().drop(1)
                            if (it.disconnected)
                                textViewClientItemAddress.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_client_disconnected_24dp, 0)
                            else if (it.hasBackpressure)
                                textViewClientItemAddress.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_client_slow_network_24dp, 0)
                            linearLayoutConnectedClients.addView(this)
                        }
                    }
                }

                is ClientsView.ToEvent.TrafficHistory -> {
                    textViewCurrentTraffic.text = getString(R.string.clients_activity_current_traffic).format(toMbit(event.trafficHistory.last().bytes))

                    val arrayOfDataPoints = event.trafficHistory.map { DataPoint(it.time.toDouble(), toMbit(it.bytes)) }.toTypedArray()
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

                is ClientsView.ToEvent.TrafficPoint -> {
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
        Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] onCreate")

        setContentView(R.layout.activity_clients)

        textViewCurrentTraffic.text = getString(R.string.clients_activity_current_traffic).format(0.0)
        textViewConnectedClients.text = getString(R.string.clients_activity_connected_clients).format(0)

        presenter.attach(this)
    }

    override fun onStart() {
        super.onStart()
        fromEvents.call(ClientsView.FromEvent.TrafficHistoryRequest)
    }

    override fun onDestroy() {
        Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] onDestroy")
        presenter.detach()
        super.onDestroy()
    }

    private fun toMbit(byte: Long) = (byte * 8).toDouble() / 1024 / 1024
}