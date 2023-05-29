package eu.darken.sdmse.common.areas.modules.pub

import dagger.Binds
import dagger.Module
import dagger.Reusable
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.sdmse.common.areas.DataArea
import eu.darken.sdmse.common.areas.modules.DataAreaModule
import eu.darken.sdmse.common.debug.logging.Logging.Priority.INFO
import eu.darken.sdmse.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.sdmse.common.debug.logging.log
import eu.darken.sdmse.common.debug.logging.logTag
import eu.darken.sdmse.common.files.APath
import eu.darken.sdmse.common.files.GatewaySwitch
import eu.darken.sdmse.common.files.canRead
import eu.darken.sdmse.common.files.local.LocalGateway
import eu.darken.sdmse.common.files.local.LocalPath
import eu.darken.sdmse.common.files.saf.SAFPath
import eu.darken.sdmse.common.hasApiLevel
import eu.darken.sdmse.common.storage.PathMapper
import javax.inject.Inject

@Reusable
class PublicDataModule @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
    private val pathMapper: PathMapper,
) : DataAreaModule {

    override suspend fun secondPass(firstPass: Collection<DataArea>): Collection<DataArea> {
        val sdcardAreas = firstPass.filter { it.type == DataArea.Type.SDCARD }

        val localGateway = gatewaySwitch.getGateway(APath.PathType.LOCAL) as LocalGateway

        val areas = sdcardAreas
            .mapNotNull { parentArea ->
                val accessPath: APath? = when {
                    hasApiLevel(33) -> {
                        when {
                            localGateway.hasRoot() -> {
                                // If we have root, we need to convert any SAFPath back
                                when (val target = parentArea.path) {
                                    is LocalPath -> target
                                    is SAFPath -> pathMapper.toLocalPath(target)
                                    else -> null
                                }
                            }
                            else -> {
                                log(TAG, INFO) { "Skipping Android/data (API33 and no root): $parentArea" }
                                null
                            }
                        }
                    }
                    hasApiLevel(30) -> {
                        val target = parentArea.path
                        // On API30 we can do the direct SAF grant workaround
                        when {
                            localGateway.hasRoot() -> when (target) {
                                is SAFPath -> pathMapper.toLocalPath(target)
                                else -> target
                            }
                            else -> when (target) {
                                is LocalPath -> pathMapper.toSAFPath(target)
                                is SAFPath -> target
                                else -> null
                            }
                        }
                    }
                    else -> parentArea.path
                }
                val childPath = accessPath?.child("Android", "data") ?: return@mapNotNull null
                parentArea to childPath
            }
            .filter {
                val canRead = it.second.canRead(gatewaySwitch)
                if (!canRead) log(TAG) { "Can't read ${it.second}" }
                canRead
            }
            .map { (parentArea, path) ->
                DataArea(
                    type = DataArea.Type.PUBLIC_DATA,
                    path = path,
                    flags = parentArea.flags,
                    userHandle = parentArea.userHandle,
                )
            }

        log(TAG, VERBOSE) { "secondPass(): $areas" }

        return areas
    }

    @InstallIn(SingletonComponent::class)
    @Module
    abstract class DIM {
        @Binds @IntoSet abstract fun mod(mod: PublicDataModule): DataAreaModule
    }

    companion object {
        val TAG: String = logTag("DataArea", "Module", "Public", "Data")
    }
}