package info.dvkr.screenstream.model.appevent

import info.dvkr.screenstream.model.AppStatus
import rx.subjects.BehaviorSubject

class AppStatusImpl : AppStatus {

//    private val mAppEvents = PublishSubject.create<AppStatus.Event>()
//    private val mAppStatus = BehaviorSubject.create<Set<String>>(HashSet<String>())
//    private val mAppStatusSet = HashSet<String>();

//    private val mInterfaces = BehaviorSubject.create<List<AppStatus.Interface>>(ArrayList<AppStatus.Interface>())
//
//    override fun sendEvent(event: AppStatus.Event) {
//        if (event is AppStatus.Event.AppStatus) {
//            when (event.status) {
//                HttpServer.HTTP_SERVER_OK -> mAppStatusSet.remove(AppStatus.APP_STATUS_ERROR_SERVER_PORT_BUSY)
//                HttpServer.HTTP_SERVER_ERROR_PORT_BUSY -> mAppStatusSet.add(AppStatus.APP_STATUS_ERROR_SERVER_PORT_BUSY)
//                ImageGenerator.IMAGE_GENERATOR_ERROR_WRONG_IMAGE_FORMAT -> mAppStatusSet.add(AppStatus.APP_STATUS_ERROR_WRONG_IMAGE_FORMAT)
//            }
//            mAppStatus.onNext(mAppStatusSet)
//        }
//        mAppEvents.onNext(event)
//    }
//
//    override fun onEvent(): Observable<AppStatus.Event> = mAppEvents.asObservable()
//
//    override fun getAppStatus(): Observable<Set<String>> = mAppStatus.asObservable()
//
//
//
//
//
//
//    override fun setInterfaceList(interfaceList: List<AppStatus.Interface>) {
//        if (interfaceList.isEmpty() && mInterfaces.value.isEmpty()) return
//        mInterfaces.onNext(interfaceList)
//    }
//
//    override fun onInterfaceList(): Observable<List<AppStatus.Interface>> = mInterfaces.asObservable()

}