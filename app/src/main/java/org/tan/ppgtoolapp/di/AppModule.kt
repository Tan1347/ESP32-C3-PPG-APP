package org.tan.ppgtoolapp.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.tan.ppgtoolapp.data.ble.BleCommandProvider
import org.tan.ppgtoolapp.data.ble.BleConnectionProvider
import org.tan.ppgtoolapp.data.ble.BleManager
import org.tan.ppgtoolapp.data.ble.BleScannerProvider
import org.tan.ppgtoolapp.data.network.DeviceHttpApi
import org.tan.ppgtoolapp.data.network.HttpRepository
import org.tan.ppgtoolapp.data.wifi.WifiScanProvider
import org.tan.ppgtoolapp.data.wifi.WifiScanner
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds @Singleton
    abstract fun bindBleScannerProvider(impl: BleManager): BleScannerProvider

    @Binds @Singleton
    abstract fun bindBleConnectionProvider(impl: BleManager): BleConnectionProvider

    @Binds @Singleton
    abstract fun bindBleCommandProvider(impl: BleManager): BleCommandProvider

    @Binds @Singleton
    abstract fun bindDeviceHttpApi(impl: HttpRepository): DeviceHttpApi

    @Binds @Singleton
    abstract fun bindWifiScanProvider(impl: WifiScanner): WifiScanProvider
}
