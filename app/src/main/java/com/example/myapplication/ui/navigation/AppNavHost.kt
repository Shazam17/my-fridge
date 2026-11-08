package com.example.myapplication.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.myapplication.AppContainer
import com.example.myapplication.ui.product.ProductDetailScreen
import com.example.myapplication.ui.product.ProductDetailViewModel
import com.example.myapplication.ui.product.ProductFormScreen
import com.example.myapplication.ui.product.ProductFormViewModel
import com.example.myapplication.ui.sections.SectionScreen
import com.example.myapplication.ui.sections.SectionViewModel
import com.example.myapplication.ui.sections.SectionsScreen
import com.example.myapplication.ui.sections.SectionsViewModel
import com.example.myapplication.ui.sync.ConnectionScreen
import com.example.myapplication.ui.sync.ConnectionViewModel
import com.example.myapplication.ui.shopping.ShoppingListScreen
import com.example.myapplication.ui.shopping.ShoppingListViewModel

object Routes {
    const val SECTIONS = "sections"
    const val SHOPPING = "shopping"
    const val CONNECTION = "connection"
    const val ADVICE = "advice"
    const val SECTION = "section/{sectionId}"
    const val PRODUCT = "product/{productId}"
    /** productId = 0 — создание нового предмета. */
    const val PRODUCT_FORM = "productForm/{sectionId}/{productId}"

    fun section(id: Long) = "section/$id"
    fun product(id: Long) = "product/$id"
    fun productForm(sectionId: Long, productId: Long = 0L) = "productForm/$sectionId/$productId"
}

@Composable
fun AppNavHost(container: AppContainer) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.SECTIONS) {

        composable(Routes.SECTIONS) {
            val vm: SectionsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { SectionsViewModel(container.sectionRepository) }
                },
            )
            val syncVm: com.example.myapplication.ui.sync.SyncViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { com.example.myapplication.ui.sync.SyncViewModel(container.syncUseCase) }
                },
            )
            SectionsScreen(
                viewModel = vm,
                onOpenSection = { navController.navigate(Routes.section(it)) },
                onOpenShopping = { navController.navigate(Routes.SHOPPING) },
                onOpenConnection = { navController.navigate(Routes.CONNECTION) },
                onOpenAdvice = { navController.navigate(Routes.ADVICE) },
                syncViewModel = syncVm,
            )
        }

        composable(
            Routes.SECTION,
            arguments = listOf(navArgument("sectionId") { type = NavType.LongType }),
        ) { entry ->
            val sectionId = entry.arguments!!.getLong("sectionId")
            val vm: SectionViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        SectionViewModel(sectionId, container.sectionRepository, container.productRepository)
                    }
                },
            )
            SectionScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenProduct = { navController.navigate(Routes.product(it)) },
                onAddProduct = { navController.navigate(Routes.productForm(sectionId)) },
            )
        }

        composable(
            Routes.PRODUCT,
            arguments = listOf(navArgument("productId") { type = NavType.LongType }),
        ) { entry ->
            val productId = entry.arguments!!.getLong("productId")
            val vm: ProductDetailViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ProductDetailViewModel(productId, container.sectionRepository, container.productRepository)
                    }
                },
            )
            ProductDetailScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(Routes.productForm(sectionId = 0L, productId = productId)) },
            )
        }

        composable(
            Routes.PRODUCT_FORM,
            arguments = listOf(
                navArgument("sectionId") { type = NavType.LongType },
                navArgument("productId") { type = NavType.LongType },
            ),
        ) { entry ->
            val sectionId = entry.arguments!!.getLong("sectionId")
            val productId = entry.arguments!!.getLong("productId")
            val vm: ProductFormViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ProductFormViewModel(
                            productId = productId.takeIf { it != 0L },
                            sections = container.sectionRepository,
                            products = container.productRepository,
                        )
                    }
                },
            )
            ProductFormScreen(
                viewModel = vm,
                initialSectionId = sectionId,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.CONNECTION) {
            val vm: ConnectionViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { ConnectionViewModel(container.syncSettingsStore) }
                },
            )
            ConnectionScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }

        composable(Routes.ADVICE) {
            val vm: com.example.myapplication.ai.AdviceViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        com.example.myapplication.ai.AdviceViewModel(
                            container.llm,
                            container.advice,
                            container.modelDownloader,
                        )
                    }
                },
            )
            com.example.myapplication.ai.AdviceScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SHOPPING) {
            val vm: ShoppingListViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { ShoppingListViewModel(container.productRepository) }
                },
            )
            ShoppingListScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenProduct = { navController.navigate(Routes.product(it)) },
            )
        }
    }
}
