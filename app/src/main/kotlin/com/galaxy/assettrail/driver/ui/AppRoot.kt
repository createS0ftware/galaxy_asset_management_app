package com.galaxy.assettrail.driver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.galaxy.assettrail.driver.ui.account.AccountScreen
import com.galaxy.assettrail.driver.ui.checkin.CheckInScreen
import com.galaxy.assettrail.driver.ui.checkin.CheckedInScreen
import com.galaxy.assettrail.driver.ui.onboarding.OnboardingScreen
import com.galaxy.assettrail.driver.ui.theme.AppIcons
import com.galaxy.assettrail.driver.ui.theme.Palette
import com.galaxy.assettrail.driver.ui.theme.Type
import com.galaxy.assettrail.driver.ui.trip.OfferScreen
import com.galaxy.assettrail.driver.ui.trip.TripScreen
import com.galaxy.assettrail.driver.ui.wallet.AdvanceScreen
import com.galaxy.assettrail.driver.ui.wallet.CashOutReceiptScreen
import com.galaxy.assettrail.driver.ui.wallet.CashOutScreen
import com.galaxy.assettrail.driver.ui.wallet.WalletScreen
import kotlinx.serialization.Serializable

@Serializable object OnboardingRoute

@Serializable object TripRoute

@Serializable object WalletRoute

@Serializable object AccountRoute

@Serializable data class OfferRoute(val tripId: String)

@Serializable data class CheckInRoute(val waypointIndex: Int)

@Serializable data class CheckedInRoute(val waypointIndex: Int)

@Serializable object AdvanceRoute

@Serializable object CashOutRoute

@Serializable data class CashOutReceiptRoute(val cashOutId: String)

@Composable
fun AppRoot() {
    val container = LocalContainer.current
    val loaded by container.repository.loaded.collectAsStateWithLifecycle()
    if (!loaded) {
        LoadingBox(Modifier.background(Palette.Ink))
        return
    }
    val state by container.repository.state.collectAsStateWithLifecycle()
    val signedIn = state.profile != null
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val startDestination: Any = remember { if (signedIn) TripRoute else OnboardingRoute }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    // Wallet set up: into the app. Demo reset: back to onboarding.
    LaunchedEffect(signedIn) {
        val onOnboarding = navController.currentDestination?.hasRoute<OnboardingRoute>() == true
        if (signedIn && onOnboarding) {
            navController.navigate(TripRoute) { popUpTo(navController.graph.id) { inclusive = true } }
        } else if (!signedIn && !onOnboarding && navController.currentDestination != null) {
            navController.navigate(OnboardingRoute) { popUpTo(navController.graph.id) { inclusive = true } }
        }
    }

    Scaffold(
        containerColor = Palette.Ink,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (signedIn && destination.isTopLevel()) BottomBar(destination, navController)
        },
    ) { padding ->
        CompositionLocalProvider(LocalSnackbar provides snackbar) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                composable<OnboardingRoute> { OnboardingScreen() }
                composable<TripRoute> {
                    TripScreen(
                        onOpenOffer = { navController.navigate(OfferRoute(it)) },
                        onCheckIn = { navController.navigate(CheckInRoute(it)) },
                        onOpenCheckIn = { navController.navigate(CheckedInRoute(it)) },
                        onRequestAdvance = { navController.navigate(AdvanceRoute) },
                        onOpenWallet = { navController.switchTab(WalletRoute) },
                    )
                }
                composable<OfferRoute> { entry ->
                    OfferScreen(
                        tripId = entry.toRoute<OfferRoute>().tripId,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<CheckInRoute> { entry ->
                    val index = entry.toRoute<CheckInRoute>().waypointIndex
                    CheckInScreen(
                        waypointIndex = index,
                        onBack = { navController.popBackStack() },
                        onFinished = {
                            navController.navigate(CheckedInRoute(index)) {
                                popUpTo<CheckInRoute> { inclusive = true }
                            }
                        },
                    )
                }
                composable<CheckedInRoute> { entry ->
                    CheckedInScreen(
                        waypointIndex = entry.toRoute<CheckedInRoute>().waypointIndex,
                        onBack = { navController.popBackStack() },
                        onOpenWallet = {
                            navController.popBackStack()
                            navController.switchTab(WalletRoute)
                        },
                    )
                }
                composable<WalletRoute> {
                    WalletScreen(
                        onCashOut = { navController.navigate(CashOutRoute) },
                        onRequestAdvance = { navController.navigate(AdvanceRoute) },
                        onOpenCashOut = { navController.navigate(CashOutReceiptRoute(it)) },
                    )
                }
                composable<AdvanceRoute> { AdvanceScreen(onBack = { navController.popBackStack() }) }
                composable<CashOutRoute> {
                    CashOutScreen(
                        onBack = { navController.popBackStack() },
                        onSubmitted = { id ->
                            navController.navigate(CashOutReceiptRoute(id)) {
                                popUpTo<CashOutRoute> { inclusive = true }
                            }
                        },
                    )
                }
                composable<CashOutReceiptRoute> { entry ->
                    CashOutReceiptScreen(
                        cashOutId = entry.toRoute<CashOutReceiptRoute>().cashOutId,
                        onDone = { navController.popBackStack() },
                    )
                }
                composable<AccountRoute> { AccountScreen() }
            }
        }
    }
}

private fun NavDestination?.isTopLevel(): Boolean =
    this != null && (hasRoute<TripRoute>() || hasRoute<WalletRoute>() || hasRoute<AccountRoute>())

private fun NavHostController.switchTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun BottomBar(destination: NavDestination?, navController: NavHostController) {
    Column(Modifier.fillMaxWidth().background(Palette.NavBar)) {
        DividerLine(Palette.Border)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        ) {
            TabItem("Trip", AppIcons.Truck, destination?.hasRoute<TripRoute>() == true) { navController.switchTab(TripRoute) }
            TabItem("Wallet", AppIcons.Wallet, destination?.hasRoute<WalletRoute>() == true) { navController.switchTab(WalletRoute) }
            TabItem("Account", AppIcons.Person, destination?.hasRoute<AccountRoute>() == true) { navController.switchTab(AccountRoute) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = if (selected) Palette.Ember else Palette.TextSecondary
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 56.dp)
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(
            label,
            style = Type.Caption.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = color,
        )
    }
}
