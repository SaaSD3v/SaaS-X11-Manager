package com.saas.x11manager.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.saas.x11manager.ui.screen.HomeScreen
import com.saas.x11manager.ui.screen.HomeViewModel
import com.saas.x11manager.ui.screen.RequirementsScreen

enum class TabItem(val title: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Requirements("Requirements", Icons.Default.FactCheck)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppNavigation(viewModel: HomeViewModel) {
    val context = LocalContext.current
    val tabs = remember { TabItem.entries }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { tabs.size })
    val selectedTab = tabs[pagerState.currentPage]
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Computer,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp).size(24.dp)
                        )
                        Text(
                            text = selectedTab.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black
                        )
                    }
                },
                windowInsets = WindowInsets.statusBars
            )
        },
        bottomBar = {
            MainBottomBar(
                selectedTab = selectedTab,
                onTabSelected = { tab ->
                    scope.launch {
                        pagerState.animateScrollToPage(tabs.indexOf(tab))
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0)
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) { page ->
            when (tabs[page]) {
                TabItem.Home -> HomeScreen(viewModel = viewModel)
                TabItem.Requirements -> RequirementsScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun MainBottomBar(
    selectedTab: TabItem,
    onTabSelected: (TabItem) -> Unit
) {
    val tabs = TabItem.entries
    val selectedIndex = tabs.indexOf(selectedTab)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                thickness = 1.dp
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .height(56.dp)
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val tabWidth = maxWidth / tabs.size
                    val offset by animateDpAsState(
                        targetValue = tabWidth * selectedIndex,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessLow
                        ),
                        label = "IndicatorOffset"
                    )

                    Surface(
                        modifier = Modifier
                            .width(tabWidth)
                            .fillMaxHeight()
                            .offset(x = offset),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ) {}
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEach { tab ->
                        val isSelected = selectedTab == tab
                        val contentColor by animateColorAsState(
                            targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                                          else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            label = "IconColor"
                        )

                        Surface(
                            onClick = { onTabSelected(tab) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            color = Color.Transparent,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(if (isSelected) 24.dp else 22.dp),
                                    tint = contentColor
                                )
                                Text(
                                    text = tab.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentColor,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = if (isSelected) 11.sp else 10.sp
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }
}
