package io.horizontalsystems.bankwallet.modules.send.tron

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.core.AppLogger
import io.horizontalsystems.bankwallet.core.HSCaution
import io.horizontalsystems.bankwallet.core.ISendTronAdapter
import io.horizontalsystems.bankwallet.core.LocalizedException
import io.horizontalsystems.bankwallet.core.ViewModelUiState
import io.horizontalsystems.bankwallet.core.managers.ConnectivityManager
import io.horizontalsystems.bankwallet.core.managers.RecentAddressManager
import io.horizontalsystems.bankwallet.core.providers.Translator
import io.horizontalsystems.bankwallet.entities.Address
import io.horizontalsystems.bankwallet.entities.ViewState
import io.horizontalsystems.bankwallet.entities.Wallet
import io.horizontalsystems.bankwallet.modules.amount.SendAmountService
import io.horizontalsystems.bankwallet.modules.contacts.ContactsRepository
import io.horizontalsystems.bankwallet.modules.send.SendResult
import io.horizontalsystems.bankwallet.modules.xrate.XRateService
import io.horizontalsystems.bankwallet.ui.compose.TranslatableString
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.Token
import io.horizontalsystems.tronkit.transaction.Fee
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.net.UnknownHostException
import io.horizontalsystems.tronkit.models.Address as TronAddress

class SendTronViewModel(
    val wallet: Wallet,
    private val sendToken: Token,
    private val feeToken: Token,
    private val adapter: ISendTronAdapter,
    private val xRateService: XRateService,
    private val amountService: SendAmountService,
    private val addressService: SendTronAddressService,
    val coinMaxAllowedDecimals: Int,
    private val contactsRepo: ContactsRepository,
    private val showAddressInput: Boolean,
    private val connectivityManager: ConnectivityManager,
    private val address: Address,
    private val recentAddressManager: RecentAddressManager
) : ViewModelUiState<SendUiState>() {
    val logger: AppLogger = AppLogger("send-tron")

    val blockchainType = wallet.token.blockchainType
    val feeTokenMaxAllowedDecimals = feeToken.decimals
    val fiatMaxAllowedDecimals = App.appConfigProvider.fiatDecimal

    private var amountState = amountService.stateFlow.value
    private var addressState = addressService.stateFlow.value
    private var feeState: FeeState = FeeState.Loading
    private var cautions: List<HSCaution> = listOf()

    var coinRate by mutableStateOf(xRateService.getRate(sendToken.coin.uid))
        private set
    var feeCoinRate by mutableStateOf(xRateService.getRate(feeToken.coin.uid))
        private set
    var confirmationData by mutableStateOf<SendTronConfirmationData?>(null)
        private set
    var sendResult by mutableStateOf<SendResult?>(null)
        private set

    init {
        viewModelScope.launch {
            amountService.stateFlow.collect {
                handleUpdatedAmountState(it)
            }
        }
        viewModelScope.launch {
            addressService.stateFlow.collect {
                handleUpdatedAddressState(it)
            }
        }
        viewModelScope.launch {
            xRateService.getRateFlow(sendToken.coin.uid).collect {
                coinRate = it
            }
        }
        viewModelScope.launch {
            xRateService.getRateFlow(feeToken.coin.uid).collect {
                feeCoinRate = it
            }
        }

        viewModelScope.launch {
            addressService.setAddress(address)
        }
    }

    override fun createState() = SendUiState(
        availableBalance = amountState.availableBalance,
        amountCaution = amountState.amountCaution,
        addressError = addressState.addressError,
        proceedEnabled = amountState.canBeSend && addressState.canBeSend,
        sendEnabled = feeState is FeeState.Success && cautions.isEmpty(),
        feeViewState = feeState.viewState,
        cautions = cautions,
        showAddressInput = showAddressInput,
        address = address
    )

    fun onEnterAmount(amount: BigDecimal?) {
        amountService.setAmount(amount)
    }

    fun onEnterAddress(address: Address?) {
        viewModelScope.launch {
            addressService.setAddress(address)
        }
    }

    fun onNavigateToConfirmation() {
        val address = addressState.address!!
        val contact = contactsRepo.getContactsFiltered(
            blockchainType = blockchainType,
            addressQuery = address.hex
        ).firstOrNull()

        confirmationData = SendTronConfirmationData(
            amount = amountState.amount!!,
            fee = null,
            activationFee = null,
            resourcesConsumed = null,
            address = address,
            contact = contact,
            coin = wallet.coin,
            feeCoin = feeToken.coin,
            isInactiveAddress = addressState.isInactiveAddress
        )

        viewModelScope.launch {
            estimateFee()
            validateBalance()
        }
    }

    private fun validateBalance() {
        val confirmationData = confirmationData ?: return
        val trxAmount = if (sendToken == feeToken) confirmationData.amount else BigDecimal.ZERO
        val totalFee = confirmationData.fee ?: return
        val availableBalance = adapter.trxBalanceData.available

        cautions = if (trxAmount + totalFee > availableBalance) {
            listOf(
                HSCaution(
                    TranslatableString.PlainString(
                        Translator.getString(
                            R.string.EthereumTransaction_Error_InsufficientBalanceForFee,
                            feeToken.coin.code
                        )
                    )
                )
            )
        } else if (sendToken == feeToken && confirmationData.amount <= BigDecimal.ZERO) {
            listOf(
                HSCaution(
                    TranslatableString.PlainString(
                        Translator.getString(
                            R.string.Tron_ZeroAmountTrxNotAllowed,
                            sendToken.coin.code
                        )
                    )
                )
            )
        } else {
            listOf()
        }
        emitState()
    }

    private suspend fun estimateFee() {
        try {
            feeState = FeeState.Loading
            emitState()

            val amount = amountState.amount!!
            val tronAddress = TronAddress.fromBase58(addressState.address!!.hex)
            val fees = adapter.estimateFee(amount, tronAddress)

            var activationFee: BigDecimal? = null
            var bandwidth: String? = null
            var energy: String? = null

            fees.forEach { fee ->
                when (fee) {
                    is Fee.AccountActivation -> {
                        activationFee = fee.feeInSuns.toBigDecimal().movePointLeft(feeToken.decimals)
                    }

                    is Fee.Bandwidth -> {
                        bandwidth = "${fee.points} Bandwidth"
                    }

                    is Fee.Energy -> {
                        val formattedEnergy = App.numberFormatter.formatNumberShort(fee.required.toBigDecimal(), 0)
                        energy = "$formattedEnergy Energy"
                    }
                }
            }

            val resourcesConsumed = if (bandwidth != null) {
                bandwidth + (energy?.let { " \n + $it" } ?: "")
            } else {
                energy
            }

            feeState = FeeState.Success(fees)
            emitState()

            val totalFee = fees.sumOf { it.feeInSuns }.toBigInteger()
            val fee = totalFee.toBigDecimal().movePointLeft(feeToken.decimals)
            val isMaxAmount = amountState.availableBalance == amountState.amount!!
            val adjustedAmount = if (sendToken == feeToken && isMaxAmount) amount - fee else amount

            confirmationData = confirmationData?.copy(
                amount = adjustedAmount,
                fee = fee,
                activationFee = activationFee,
                resourcesConsumed = resourcesConsumed
            )
        } catch (error: Throwable) {
            logger.warning("estimate error", error)

            cautions = listOf(createCaution(error))
            feeState = FeeState.Error(error)
            emitState()

            confirmationData = confirmationData?.copy(fee = null, activationFee = null, resourcesConsumed = null)
        }
    }

    fun onClickSend() {
        logger.info("click send button")

        viewModelScope.launch {
            send()
        }
    }

    fun hasConnection(): Boolean {
        return connectivityManager.isConnected
    }

    private suspend fun send() = withContext(Dispatchers.IO) {
        try {
            val confirmationData = confirmationData ?: return@withContext
            sendResult = SendResult.Sending
            logger.info("sending tx")

            val amount = confirmationData.amount
            adapter.send(amount, addressState.tronAddress!!, feeState.feeLimit)

            sendResult = SendResult.Sent()
            logger.info("success")

            recentAddressManager.setRecentAddress(addressState.address!!, BlockchainType.Tron)
        } catch (e: Throwable) {
            sendResult = SendResult.Failed(createCaution(e))
            logger.warning("failed", e)
        }
    }

    private fun createCaution(error: Throwable) = when (error) {
        is UnknownHostException -> HSCaution(TranslatableString.ResString(R.string.Hud_Text_NoInternet))
        is LocalizedException -> HSCaution(TranslatableString.ResString(error.errorTextRes))
        else -> HSCaution(TranslatableString.PlainString(error.message ?: ""))
    }

    private fun handleUpdatedAmountState(amountState: SendAmountService.State) {
        this.amountState = amountState

        emitState()
    }

    private fun handleUpdatedAddressState(addressState: SendTronAddressService.State) {
        this.addressState = addressState

        emitState()
    }
}

sealed class FeeState {
    object Loading : FeeState()
    data class Success(val fees: List<Fee>) : FeeState()
    data class Error(val error: Throwable) : FeeState()

    val viewState: ViewState
        get() = when (this) {
            is Error -> ViewState.Error(error)
            Loading -> ViewState.Loading
            is Success -> ViewState.Success
        }

    val feeLimit: Long?
        get() = when (this) {
            is Error -> null
            Loading -> null
            is Success -> {
                (fees.find { it is Fee.Energy } as? Fee.Energy)?.feeInSuns
            }
        }
}
