package info.dvkr.screenstream.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.view.LayoutInflater
import com.jjoe64.graphview.DefaultLabelFormatter
import com.jjoe64.graphview.GridLabelRenderer
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.presenter.BaseView
import info.dvkr.screenstream.data.presenter.clients.ClientsPresenter
import info.dvkr.screenstream.data.presenter.clients.ClientsView
import info.dvkr.screenstream.domain.utils.Utils
import kotlinx.android.synthetic.main.activity_clients.*
import kotlinx.android.synthetic.main.avtivity_clients_client_item.view.*
import org.koin.android.architecture.ext.getViewModel
import timber.log.Timber
import java.text.NumberFormat


class ClientsActivity : BaseActivity(), ClientsView {

    companion object {
        fun getStartIntent(context: Context): Intent = Intent(context, ClientsActivity::class.java)
    }

    private val presenter: ClientsPresenter by lazy { getViewModel<ClientsPresenter>() }

    private var lineGraphSeries: LineGraphSeries<DataPoint>? = null

    override fun toEvent(toEvent: BaseView.BaseToEvent) = runOnUiThread {
        Timber.d("[${Utils.getLogPrefix(this)}] toEvent: ${toEvent.javaClass.simpleName}")

        when (toEvent) {
            is ClientsView.ToEvent.CurrentClients -> {
                val clientsCount = toEvent.clientsList.filter { !it.disconnected }.count()
                textViewConnectedClients.text = getString(R.string.clients_activity_connected_clients).format(clientsCount)

                linearLayoutConnectedClients.removeAllViews()
                val layoutInflater = LayoutInflater.from(this)
                toEvent.clientsList.forEach {
                    with(layoutInflater.inflate(R.layout.avtivity_clients_client_item, linearLayoutConnectedClients, false)) {
                        textViewClientItemAddress.text = getString(R.string.clients_activity_client).format(it.clientAddress.toString().drop(1))
                        when {
                            it.disconnected ->
                                textViewClientItemAddress.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_client_disconnected_24dp, 0)

                            it.hasBackpressure ->
                                textViewClientItemAddress.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_client_slow_network_24dp, 0)
                        }
                        linearLayoutConnectedClients.addView(this)
                    }
                }
            }

            is ClientsView.ToEvent.TrafficHistory -> {
                textViewCurrentTraffic.text = getString(R.string.clients_activity_current_traffic).format(toMbit(toEvent.trafficHistory.last().bytes))

                val arrayOfDataPoints = toEvent.trafficHistory.map { DataPoint(it.time.toDouble(), toMbit(it.bytes)) }.toTypedArray()
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
                val maxY = Math.max(toMbit(toEvent.maxY) * 1.2, 1.1)
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
                    val mbit = toMbit(toEvent.trafficPoint.bytes)
                    textViewCurrentTraffic.text = getString(R.string.clients_activity_current_traffic).format(mbit)
                    it.appendData(DataPoint(toEvent.trafficPoint.time.toDouble(), mbit), true, 60)
                    val maxY = Math.max(toMbit(toEvent.maxY) * 1.2, 1.1)
                    lineChartTraffic.viewport.setMinY(maxY * -0.1)
                    lineChartTraffic.viewport.setMaxY(maxY)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clients)

        textViewCurrentTraffic.text = getString(R.string.clients_activity_current_traffic).format(0.0)
        textViewConnectedClients.text = getString(R.string.clients_activity_connected_clients).format(0)

        presenter.attach(this)
    }

    override fun onStart() {
        super.onStart()
        presenter.offer(ClientsView.FromEvent.TrafficHistoryRequest)
    }

    override fun onDestroy() {
        presenter.detach()
        super.onDestroy()
    }

    private fun toMbit(byte: Long) = (byte * 8).toDouble() / 1024 / 1024
}