/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import * as vscode from "vscode";
import * as utils from "./utils";
import { ThemeIcon } from "vscode";
import { getWebviewContent } from "./webviewContent";

const KARAVAN_PANELS: Map<string, vscode.WebviewPanel> = new Map<string, vscode.WebviewPanel>();

export class HelpView implements vscode.TreeDataProvider<HelpItem> {

	constructor(private context: vscode.ExtensionContext) {

	}
	private _onDidChangeTreeData: vscode.EventEmitter<HelpItem | undefined | void> = new vscode.EventEmitter<HelpItem | undefined | void>();
	readonly onDidChangeTreeData: vscode.Event<HelpItem | undefined | void> = this._onDidChangeTreeData.event;

	getTreeItem(element: HelpItem): vscode.TreeItem | Thenable<vscode.TreeItem> {
		return element;
	}
	getChildren(element?: HelpItem): vscode.ProviderResult<HelpItem[]> {
		const helpItems: HelpItem[] = [];
		helpItems.push(new HelpItem("Knowledgebase", "Knowledgebase", "knowledgebase", 'combine', { command: 'karavan.openKnowledgebase', title: '' }));
		helpItems.push(new HelpItem("Report issue", "Report Issue", "issue", 'comment', { command: 'karavan.reportIssue', title: '' }));
		return Promise.resolve(helpItems);
	}

	refresh(): void {
		this._onDidChangeTreeData.fire();
	}

	openKaravanWebView(page: string) {
		if (!KARAVAN_PANELS.has(page)) {
			// Karavan webview
			const panel = vscode.window.createWebviewPanel(
				"karavan",
				page.toUpperCase(),
				vscode.ViewColumn.One,
				{
					enableScripts: true,
					retainContextWhenHidden: true,
					localResourceRoots: [
						vscode.Uri.joinPath(this.context.extensionUri, "dist"),
					],
				}
			);
			panel.webview.html = getWebviewContent(this.context, panel.webview);
			panel.iconPath = vscode.Uri.joinPath(
				this.context.extensionUri,
				"icons/karavan.svg"
			);

			// Handle messages from the webview
			panel.webview.onDidReceiveMessage(
				message => {
					switch (message.command) {
						case 'getData':
							this.sendData(panel, page);
							break;
					}
				},
				undefined,
				this.context.subscriptions
			);
			// Handle close event
			panel.onDidDispose(() => {
				KARAVAN_PANELS.delete(page);
			}, null, this.context.subscriptions);

			// Handle reopen
			panel.onDidChangeViewState((e: vscode.WebviewPanelOnDidChangeViewStateEvent) => {
				if (e.webviewPanel.active) {
					e.webviewPanel.webview.postMessage({ command: 'reread' })
				}
			});

			KARAVAN_PANELS.set(page, panel);
		} else {
			KARAVAN_PANELS.get(page)?.reveal(undefined, true);
		}
	}

	 sendData(panel: vscode.WebviewPanel, page: string) {
		// Read and send Kamelets
		utils.readKamelets(this.context).then(kamelets => {
			panel.webview.postMessage({ command: 'kamelets', kamelets: kamelets });
		}).finally(() => {
			utils.readComponents(this.context).then(components => {
				// Read and send Components
				panel.webview.postMessage({ command: 'components', components: components });
			}).finally(() => {
				// Send integration
				panel.webview.postMessage({ command: 'open', page: page });
			})
		})
	}
}

export class HelpItem extends vscode.TreeItem {

	constructor(
		public readonly title: string,
		public readonly tooltip: string,
		public readonly page: string,
		public readonly icon: string,
		public readonly command?: vscode.Command
	) {
		super(title, vscode.TreeItemCollapsibleState.None);
		this.tooltip = this.tooltip;
	}

	iconPath = new ThemeIcon(this.icon);

	contextValue = 'help';
}