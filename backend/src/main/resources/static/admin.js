'use strict';

/**
 * Access token хранится только в памяти вкладки. Refresh token браузер получает
 * в HttpOnly/SameSite cookie.
 */
const state = {
    accessToken: null,
    user: null,
    records: [],
    orders: { page: 0, size: 25, totalPages: 0, query: '', content: [] },
    usersPage:0,auditPage:0,selectedStoreOrderIds: new Set(),
    activeStoreOrderForNotes: null,
    refreshPromise: null
};

const $ = (id) => document.getElementById(id);
const escapeHtml = (value) => String(value ?? '').replace(/[&<>'"]/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[ch]));
const roles = () => new Set((state.user?.roles || []).map(String));
const isAdmin = () => roles().has('ADMIN');
const canEdit = () => roles().has('MANAGER') || isAdmin();


/**
 * Коды ролей в API остаются английскими; в интерфейсе используются русские названия.
 */
function roleLabel(value) {
    return ({
        ADMIN: 'Администратор',
        MANAGER: 'Менеджер',
        VIEWER: 'Наблюдатель'
    })[value] || value;
}

/** Русские имена полей для ошибок Bean Validation. */
function fieldLabel(value) {
    return ({
        externalId: 'внешний ID',
        albumTitle: 'альбом',
        artist: 'исполнитель',
        customerName: 'покупатель',
        email: 'email',
        phone: 'телефон',
        orderedOn: 'дата заказа',
        recordId: 'пластинка',
        status: 'статус',
        totalPrice: 'сумма заказа',
        paid: 'оплата',
        tags: 'теги',
        version: 'версия'
    })[value] || value;
}

/** Приводит первую букву текста после двоеточия к нижнему регистру. */
function lowerFirst(value) {
    const text = String(value ?? '');
    return text ? text.charAt(0).toLocaleLowerCase('ru-RU') + text.slice(1) : text;
}

/**
 * Нормализует сообщения вида «поле: Ошибка» в «поле: ошибка».
 * Правило применяется и к сообщениям сервера.
 */
function normalizeAfterColon(value) {
    return String(value ?? '').replace(/:\s*([А-ЯЁA-Z])/g,
        (_, letter) => `: ${letter.toLocaleLowerCase('ru-RU')}`);
}

/**
 * Приводит ввод телефона к формату +7-999-999-99-99.
 * Если пользователь вставляет 8XXXXXXXXXX или 7XXXXXXXXXX, код страны
 * автоматически нормализуется к +7.
 */
function formatRussianPhone(value) {
    let digits = String(value ?? '').replace(/\D/g, '');

    if (digits.startsWith('8')) {
        digits = `7${digits.slice(1)}`;
    }

    if (digits.startsWith('7')) {
        digits = digits.slice(1);
    }

    digits = digits.slice(0, 10);

    let result = '+7-';
    if (!digits.length) return result;

    result += digits.slice(0, 3);
    if (digits.length > 3) result += `-${digits.slice(3, 6)}`;
    if (digits.length > 6) result += `-${digits.slice(6, 8)}`;
    if (digits.length > 8) result += `-${digits.slice(8, 10)}`;

    return result;
}

/**
 * Маска телефона:
 * - при фокусе сразу подставляет +7-;
 * - разрешает вводить только цифры и служебные клавиши;
 * - дефисы добавляет автоматически;
 * - вставленный текст очищает от букв и лишних знаков.
 */
function bindPhoneMask(input) {
    if (!input) return;

    input.addEventListener('focus', () => {
        if (!input.value) input.value = '+7-';
    });

    input.addEventListener('keydown', event => {
        const allowedKeys = new Set([
            'Backspace', 'Delete', 'ArrowLeft', 'ArrowRight',
            'Home', 'End', 'Tab', 'Enter'
        ]);

        if (event.ctrlKey || event.metaKey || allowedKeys.has(event.key) || /^\d$/.test(event.key)) {
            return;
        }

        event.preventDefault();
    });

    input.addEventListener('input', () => {
        input.value = formatRussianPhone(input.value);
    });

    input.addEventListener('blur', () => {
        if (input.value === '+7-') input.value = '';
    });
}

function toast(message, error = false) {
    const node = $('toast');
    node.textContent = message;
    node.className = `toast show${error ? ' error' : ''}`;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => node.className = 'toast', 3500);
}


/**
 * Ошибки modal dialog выводятся внутри формы, а не через общий toast.
 */
function showFormError(form, message) {
    const box = form.querySelector('[data-form-error]');
    if (!box) {
        toast(message, true);
        return;
    }
    box.textContent = message;
    box.hidden = false;
}

/** Очищает локальное сообщение об ошибке перед новой отправкой формы. */
function clearFormError(form) {
    const box = form.querySelector('[data-form-error]');
    if (!box) return;
    box.textContent = '';
    box.hidden = true;
}

async function parseError(response) {
    try {
        const body = await response.json();
        const fields = body.validationErrors
            ? Object.entries(body.validationErrors)
                .map(([key, value]) => `${fieldLabel(key)}: ${lowerFirst(value)}`)
                .join('; ')
            : '';

        const message = `${body.message || response.statusText}${fields ? ` — ${fields}` : ''}${body.correlationId ? ` [${body.correlationId}]` : ''}`;
        return normalizeAfterColon(message);
    } catch (_) {
        return `${response.status} ${response.statusText}`;
    }
}

/**
 * Общая функция HTTP-вызова обрабатывает 401 и ограничивает повторный refresh.
 * Параллельные запросы используют один refreshPromise.
 */
async function api(path, options = {}, retry = true) {
    const headers = new Headers(options.headers || {});
    if (state.accessToken) headers.set('Authorization', `Bearer ${state.accessToken}`);
    if (options.body && !(options.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    const response = await fetch(`/api${path}`, {...options, headers, credentials: 'include'});
    if (response.status === 401 && retry) {
        const refreshed = await refreshAccessToken();
        if (refreshed) return api(path, options, false);
    }
    if (!response.ok) throw new Error(await parseError(response));
    if (response.status === 204) return null;
    const type = response.headers.get('content-type') || '';
    return type.includes('application/json') ? response.json() : response;
}

async function refreshAccessToken() {
    if (!state.refreshPromise) {
        state.refreshPromise = fetch('/api/auth/refresh', {
            method: 'POST', credentials: 'include', headers: {'Content-Type':'application/json'}, body: '{}'
        }).then(async response => {
            if (!response.ok) return false;
            const result = await response.json();
            state.accessToken = result.accessToken;
            state.user = result.user;
            return true;
        }).finally(() => state.refreshPromise = null);
    }
    return state.refreshPromise;
}

async function boot() {
    bindEvents();
    if (await refreshAccessToken()) {
        await enterApplication();
    } else {
        showLogin();
    }
}

function showLogin() {
    $('app-view').hidden = true;
    $('login-view').hidden = false;
    state.accessToken = null;
    state.user = null;
}

async function enterApplication() {
    $('login-view').hidden = true;
    $('app-view').hidden = false;
    applyRoleVisibility();
    $('current-user').innerHTML = `<strong>${escapeHtml(state.user.displayName)}</strong><br>${escapeHtml(state.user.email)}<br>${escapeHtml(state.user.roles.map(roleLabel).join(', '))}`;

    if (state.user.passwordChangeRequired) {
        toast('Для продолжения необходимо сменить временный пароль', true);
        const changed = await openPasswordDialog(true);
        if (!changed) return;
    }

    /*
     * Ошибка загрузки аналитики или справочников не считается ошибкой входа.
     * Пользователь остаётся в приложении и получает отдельное уведомление.
     */
    try {
        await Promise.all([loadRecords(), loadDashboard()]);
    } catch (error) {
        console.error('[SPIN · Управление] Initial data loading failed', error);
        toast(`Вход выполнен, но часть данных не загрузилась: ${error.message}`, true);
    }
}

function applyRoleVisibility() {
    document.querySelectorAll('.admin-only').forEach(node => node.hidden = !isAdmin());
    document.querySelectorAll('.manager-only').forEach(node => node.hidden = !canEdit());
}

function switchTab(name) {
    document.querySelectorAll('.tab').forEach(tab => tab.classList.toggle('active', tab.id === `tab-${name}`));
    document.querySelectorAll('.nav-item').forEach(button => button.classList.toggle('active', button.dataset.tab === name));
    const titles = {
        dashboard: ['Аналитика','Сводная картина по заказам'],
        orders: ['Заказы','Умный поиск, карточки и массовые операции'],
        records: ['Пластинки','Единый справочник пластинок'],
        users: ['Пользователи','Роли, блокировка и временные пароли'],
        audit: ['Журнал аудита','История действий пользователей']
    };
    [$('page-title').textContent, $('page-subtitle').textContent] = titles[name];
    if (name === 'orders') loadStoreOrders();
    if (name === 'records') renderRecords();
    if (name === 'users' && isAdmin()) loadUsers();
    if (name === 'audit' && isAdmin()) loadAudit();
    if (name === 'dashboard') loadDashboard();
}

async function loadDashboard() {
    try {
        const data = await api('/analytics/summary');
        const metrics = [
            ['Всего', data.total], ['Активные', data.active], ['В архиве', data.archived],
            ['С оплатаю', data.paidCount], ['Средняя сумма, ₽', data.averageTotalPrice ?? '—']
        ];
        $('metric-grid').innerHTML = metrics.map(([name,value]) => `<article class="metric card"><span class="muted">${name}</span><strong>${value}</strong></article>`).join('');
        renderBars('chart-records', data.byRecord);
        renderBars('chart-status', data.byStatus);
        renderBars('chart-priority', data.byPriority);
    } catch (error) { toast(error.message, true); }
}

function renderBars(id, values) {
    const max = Math.max(1, ...values.map(item => item.count));

    const displayName = value => {
        if (id === 'chart-status') return statusLabel(value);
        if (id === 'chart-priority') return priorityLabel(value);
        return value;
    };

    $(id).innerHTML = values.length
        ? values.map(item => `<div class="bar-row"><span>${escapeHtml(displayName(item.name))}</span><progress value="${item.count}" max="${max}"></progress><strong>${item.count}</strong></div>`).join('')
        : '<p class="muted">Нет данных</p>';
}

async function loadRecords() {
    try { state.records = await api('/records?activeOnly=false'); renderRecords(); }
    catch (error) { toast(error.message, true); }
}

function renderRecords() {
    const body = $('records-body');
    if (!body) return;
    body.innerHTML = state.records.map(record => `<tr>
        <td><strong>${escapeHtml(record.code)}</strong></td><td>${escapeHtml(record.name || '—')}</td><td>${record.releaseYear ?? '—'}</td>
        <td><span class="badge ${record.active ? 'success' : ''}">${record.active ? 'Да' : 'Нет'}</span></td>
        <td class="admin-only"><div class="actions"><button class="secondary" data-record-edit="${record.id}">Изменить</button><button class="secondary" data-record-toggle="${record.id}">${record.active ? 'Отключить' : 'Включить'}</button></div></td>
    </tr>`).join('');
    applyRoleVisibility();
}

async function loadStoreOrders(page = state.orders.page) {
    try {
        const q = encodeURIComponent(state.orders.query);
        const result = await api(`/orders?q=${q}&page=${page}&size=${state.orders.size}&sort=${$('orders-sort').value}`);
        state.orders = {...state.orders, ...result};
        state.selectedStoreOrderIds.clear();
        renderStoreOrders();
    } catch (error) { toast(error.message, true); }
}

function renderStoreOrders() {
    $('orders-body').innerHTML = state.orders.content.map(order => `<tr class="${order.archived ? 'archived' : ''}">
        <td class="manager-only"><input type="checkbox" data-order-select="${order.id}"></td>
        <td><strong>${escapeHtml(order.displayTitle)}</strong><br><small class="muted">${escapeHtml(order.externalId || '')}</small></td>
        <td>${escapeHtml(order.recordCode || '—')}</td><td>${escapeHtml(statusLabel(order.status))}</td>
        <td>${order.totalPrice ?? '—'}</td><td>${order.paid ? '<span class="badge warning">Да</span>' : 'Нет'}</td>
        <td><span class="badge priority-${order.priorityLevel}">${escapeHtml(priorityLabel(order.priorityLevel))}</span></td>
        <td>${order.tags.map(tag => `<span class="badge">${escapeHtml(tag)}</span>`).join(' ') || '—'}</td>
        <td><div class="actions">
            <button class="secondary" data-order-view="${order.id}">Карточка</button>
            ${isAdmin()&&order.archived?`<button class="danger-outline" data-permanent="${order.id}">Удалить навсегда</button>`:''}
            ${canEdit() ? `<button class="secondary" data-order-edit="${order.id}">Изменить</button><button class="secondary" data-order-notes="${order.id}">Заметки</button><button class="danger-outline" data-order-archive="${order.id}">${order.archived ? 'Восстановить' : 'В архив'}</button>` : ''}
        </div></td>
    </tr>`).join('') || '<tr><td colspan="9" class="muted">Заказы не найдены</td></tr>';
    $('orders-page').textContent = `Страница ${state.orders.page + 1} из ${Math.max(1,state.orders.totalPages)} · записей ${state.orders.totalElements}`;
    $('orders-prev').disabled = state.orders.first;
    $('orders-next').disabled = state.orders.last;
    $('select-all-orders').checked = false;
    applyRoleVisibility();
}

function orderForm(order = null, readOnly = false) {
    const recordOptions = ['<option value="">Без пластинки</option>', ...state.records.filter(g => g.active || g.id === order?.recordId).map(g => `<option value="${g.id}" ${g.id === order?.recordId ? 'selected' : ''}>${escapeHtml(g.code)}</option>`)].join('');
    return `<div class="dialog-head"><h3>${order ? (readOnly ? 'Карточка заказа' : 'Изменение заказа') : 'Новый заказ'}</h3><button type="button" data-close-dialog="entity-dialog" class="icon-button">×</button></div>
    <div class="form-grid">
      <label>Исполнитель<input name="artist" required maxlength="100" value="${escapeHtml(order?.artist || '')}" ${readOnly?'disabled':''}></label>
      <label>Альбом<input name="albumTitle" required maxlength="100" value="${escapeHtml(order?.albumTitle || '')}" ${readOnly?'disabled':''}></label>
      <label>Покупатель<input name="customerName" required maxlength="100" value="${escapeHtml(order?.customerName || '')}" ${readOnly?'disabled':''}></label>
      <label>External ID<input name="externalId" required maxlength="100" value="${escapeHtml(order?.externalId || '')}" ${readOnly?'disabled':''}></label>
      <label>Email<input name="email" type="email" maxlength="254" value="${escapeHtml(order?.email || '')}" ${readOnly?'disabled':''}></label>
      <label>Телефон<input name="phone" type="tel" inputmode="numeric" autocomplete="tel" maxlength="16" pattern="\\+7-[0-9]{3}-[0-9]{3}-[0-9]{2}-[0-9]{2}" title="Формат: +7-999-999-99-99" placeholder="+7-999-999-99-99" value="${escapeHtml(order?.phone ? formatRussianPhone(order.phone) : '')}" ${readOnly?'disabled':''}></label>
      <label>Дата заказа<input name="orderedOn" type="date" max="${new Date().toISOString().slice(0,10)}" value="${order?.orderedOn || ''}" ${readOnly?'disabled':''}></label>
      <label>Пластинка<select name="recordId" required ${readOnly?'disabled':''}>${recordOptions}</select></label>
      <label>Статус<select name="status" ${readOnly?'disabled':''}>${statusOptions(order?.status)}</select></label>
      <label>Сумма заказа, ₽<input name="totalPrice" required type="number" min="0.01" max="9999999999.99" step="0.01" inputmode="decimal" placeholder="Например, 4990.00" value="${order?.totalPrice ?? ''}" ${readOnly?'disabled':''}></label>
      <label>Количество<input name="quantity" type="number" min="1" max="1000" value="${order?.quantity??1}" ${readOnly?'disabled':''}></label><label class="inline"><input name="paid" type="checkbox" ${order?.paid?'checked':''} ${readOnly?'disabled':''}> Оплачен</label>
      <label class="span-2">Теги через запятую<input name="tags" maxlength="1000" value="${escapeHtml((order?.tags || []).join(', '))}" ${readOnly?'disabled':''}></label>
    </div>
    ${readOnly ? '' : '<div class="form-message error" data-form-error hidden></div><button class="primary" type="submit">Сохранить</button>'}`;
}

async function openStoreOrder(id = null, readOnly = false) {
    const order = id ? await api(`/orders/${id}`) : null;
    const form = $('entity-form');
    form.innerHTML = orderForm(order, readOnly);

    const phoneInput = form.elements.phone;
    if (phoneInput && !readOnly) {
        bindPhoneMask(phoneInput);
    }

    form.onsubmit = readOnly ? null : async event => {
        event.preventDefault();
        clearFormError(form);

        /*
         * reportValidity() запускает нативную browser-валидацию до похода в backend.
         * Так пользователь сразу видит проблему в конкретном поле.
         */
        if (!form.reportValidity()) return;

        const data = new FormData(form);
        const payload = {
            externalId: empty(data.get('externalId')), albumTitle: data.get('albumTitle'), artist: data.get('artist'), customerName: empty(data.get('customerName')),
            email: empty(data.get('email')), phone: empty(String(data.get('phone')||'').replaceAll('-','')), orderedOn: empty(data.get('orderedOn')),
            recordId: empty(data.get('recordId')), status: data.get('status'), totalPrice: empty(data.get('totalPrice')) ? Number(data.get('totalPrice')) : null,
            quantity:Number(data.get('quantity')||1), paid: data.get('paid') === 'on', tags: String(data.get('tags') || '').split(',').map(v => v.trim()).filter(Boolean), version: order?.version ?? null
        };
        try {
            await api(id ? `/orders/${id}` : '/orders', {method: id ? 'PUT' : 'POST', body: JSON.stringify(payload)});
            $('entity-dialog').close(); toast('Карточка сохранена'); await Promise.all([loadStoreOrders(), loadDashboard()]);
        } catch (error) {
            showFormError(form, error.message);
        }
    };
    $('entity-dialog').showModal();
}

async function archiveStoreOrder(id) {
    const order = state.orders.content.find(item => item.id === id);
    const action = order?.archived ? 'восстановить' : 'переместить в архив';
    if (!confirm(`Действительно ${action} карточку?`)) return;
    try {
        await api(order.archived ? `/orders/${id}/restore` : `/orders/${id}`, {method: order.archived ? 'POST' : 'DELETE'});
        toast('Операция выполнена'); await Promise.all([loadStoreOrders(), loadDashboard()]);
    } catch (error) { toast(error.message, true); }
}

async function openNotes(orderId) {
    state.activeStoreOrderForNotes = orderId;
    await renderNotes();
    $('notes-dialog').showModal();
}

async function renderNotes() {
    try {
        const list = await api(`/orders/${state.activeStoreOrderForNotes}/notes`);
        $('notes-list').innerHTML = list.map(note => `<article class="note"><div class="note-meta"><span>${escapeHtml(note.authorName)} · ${formatDate(note.createdAt)}</span><span>${note.pinned ? '📌' : ''}</span></div><p>${escapeHtml(note.body).replace(/\n/g,'<br>')}</p><div class="actions"><button class="danger-outline" data-note-edit="${note.id}">Изменить</button><button class="danger-outline" data-note-delete="${note.id}">Удалить</button></div></article>`).join('') || '<p class="muted">Заметок пока нет</p>';
    } catch (error) { toast(error.message, true); }
}

async function bulkAction() {
    if (!state.selectedStoreOrderIds.size) return toast('Выберите хотя бы одного заказа', true);
    const action = prompt('Действие: ARCHIVE, RESTORE, SET_STATUS, ADD_TAG, REMOVE_TAG', 'ARCHIVE');
    if (!action) return;
    const payload = {ids: [...state.selectedStoreOrderIds], action: action.toUpperCase(), status: null, tag: null};
    if (payload.action === 'SET_STATUS') payload.status = prompt('Статус: NEW, PAID, CANCELED, COMPLETED, SHIPPED', 'NEW');
    if (payload.action === 'ADD_TAG' || payload.action === 'REMOVE_TAG') payload.tag = prompt('Тег');
    try {
        const result = await api('/orders/bulk', {method:'POST', body:JSON.stringify(payload)});
        toast(`Успешно: ${result.succeeded}; ошибок: ${result.failures.length}`); await Promise.all([loadStoreOrders(), loadDashboard()]);
    } catch (error) { toast(error.message, true); }
}

async function exportCsv() {
    try {
        const response = await api(`/orders/export?q=${encodeURIComponent(state.orders.query)}`);
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a'); link.href = url; link.download = `orders-${new Date().toISOString().slice(0,10)}.csv`; link.click();
        URL.revokeObjectURL(url);
    } catch (error) { toast(error.message, true); }
}

function recordForm(record = null) {
    return `<div class="dialog-head"><h3>${record?'Изменение':'Новая'} пластинка</h3><button type="button" data-close-dialog="entity-dialog" class="icon-button">×</button></div>
    <label>Код<input name="code" required maxlength="50" value="${escapeHtml(record?.code || '')}"></label>
    <label>Название<input name="name" required maxlength="200" value="${escapeHtml(record?.name || '')}"></label>
    <label>Год выпуска<input name="releaseYear" type="number" min="1900" max="2100" value="${record?.releaseYear ?? ''}"></label>
    <label>Исполнитель<input name="artist" required maxlength="120" value="${escapeHtml(record?.artist||'')}"></label>
    <label>Жанр<input name="genre" maxlength="50" value="${escapeHtml(record?.genre||'Rock')}"></label>
    <label>Цена<input name="price" type="number" required min="0.01" max="99999999.99" step="0.01" value="${record?.price??4990}"></label>
    <label>Остаток<input name="stock" type="number" required min="0" value="${record?.stock??10}"></label>
    <label>Изображение<select name="cover">${['1-imgImage50','1-imgImage49','1-imgImage48','1-imgImage51','1-imgImage52','1-imgImage53','1-imgImage55','1-imgImage56','1-imgImage57','1-imgImage58','1-imgImage59','1-imgImage60','2-imgImage60'].map((v,i)=>`<option value="/assets/${v}.png" ${record?.cover===`/assets/${v}.png`?'selected':''}>${['Californication','Weezer','OK Computer','Random Access','Meteora','Best Of Pixies','Flower Boy','Wuthering Heights','Prima','Midnight Sun','Sling','Ultraviolence','Stranger In The Alps'][i]}</option>`).join('')}</select></label>
    <label>Описание<textarea name="description" maxlength="2000">${escapeHtml(record?.description||'')}</textarea></label>
    <label class="inline"><input name="active" type="checkbox" ${record?.active !== false?'checked':''}> Активна</label>
    <button class="primary">Сохранить</button>`;
}

async function openRecord(id = null) {
    window.location.href = id ? `/admin?edit=${encodeURIComponent(id)}` : '/admin?new=1';
}

async function toggleRecord(id) {
    const record = state.records.find(g => g.id === id);
    try { await api(`/records/${id}/active?active=${!record.active}&version=${record.version}`,{method:'PATCH'}); await loadRecords(); }
    catch (error) { toast(error.message,true); }
}

async function loadUsers(pageNumber=state.usersPage) {
    if(typeof pageNumber!=='number')pageNumber=0;state.usersPage=pageNumber;
    try {
        const page = await api(`/admin/users?page=${pageNumber}&size=25&sort=email,asc`);
        $('users-body').innerHTML = page.content.map(user => `<tr><td>${escapeHtml(user.email)}</td><td>${escapeHtml(user.displayName)}</td><td>${user.roles.map(r=>`<span class="badge">${escapeHtml(roleLabel(r))}</span>`).join(' ')}</td><td>${user.enabled?'Да':'Нет'}</td><td>${user.passwordChangeRequired?'Да':'Нет'}</td><td><div class="actions"><button class="secondary" data-user-edit="${user.id}">Изменить</button><button class="secondary" data-user-reset="${user.id}">Сбросить пароль</button></div></td></tr>`).join('');
        state.users = page.content;
        $('users-prev').disabled=page.first;$('users-next').disabled=page.last;$('users-page').textContent=`${page.page+1} / ${Math.max(1,page.totalPages)}`;
    } catch (error) { toast(error.message,true); }
}

function userForm(user = null) {
    const checked = role => user?.roles.includes(role) || (!user && role==='VIEWER');
    return `<div class="dialog-head"><h3>${user?'Изменение':'Новый'} пользователь</h3><button type="button" data-close-dialog="entity-dialog" class="icon-button">×</button></div>
    <label>Email<input name="email" type="email" required maxlength="254" value="${escapeHtml(user?.email||'')}"></label>
    <label>Отображаемое имя<input name="displayName" required maxlength="160" value="${escapeHtml(user?.displayName||'')}"></label>
    ${user?'': '<label>Временный пароль<input name="temporaryPassword" type="password" required minlength="12" maxlength="128"></label>'}
    <fieldset><legend>Роли</legend>${['VIEWER','MANAGER','ADMIN'].map(r=>`<label class="inline"><input type="checkbox" name="roles" value="${r}" ${checked(r)?'checked':''}> ${escapeHtml(roleLabel(r))}</label>`).join('')}</fieldset>
    ${user?`<label class="inline"><input type="checkbox" name="enabled" ${user.enabled?'checked':''}> Активен</label>`:''}
    <button class="primary">Сохранить</button>`;
}

async function openUser(id = null) {
    const user = id ? state.users.find(u=>u.id===id) : null;
    const form=$('entity-form'); form.innerHTML=userForm(user);
    form.onsubmit=async event=>{event.preventDefault(); const data=new FormData(form); const payload={email:data.get('email'),displayName:data.get('displayName'),roles:data.getAll('roles'),...(user?{enabled:data.get('enabled')==='on',version:user.version}:{temporaryPassword:data.get('temporaryPassword')})};
        try{await api(id?`/admin/users/${id}`:'/admin/users',{method:id?'PUT':'POST',body:JSON.stringify(payload)});$('entity-dialog').close();toast('Пользователь сохранён');await loadUsers();}catch(error){toast(error.message,true);}};
    $('entity-dialog').showModal();
}

async function resetUserPassword(id) {
    const password=prompt('Новый временный пароль (не менее 12 символов, регистры, цифра и спецсимвол)'); if(!password)return;
    try{await api(`/admin/users/${id}/reset-password`,{method:'POST',body:JSON.stringify({temporaryPassword:password})});toast('Пароль сброшен');await loadUsers();}catch(error){toast(error.message,true);}
}

async function loadAudit(pageNumber=state.auditPage) {
    if(typeof pageNumber!=='number')pageNumber=0;state.auditPage=pageNumber;
    try {
        const q=encodeURIComponent($('audit-search').value.trim()); const page=await api(`/admin/audit?q=${q}&page=${pageNumber}&size=25&sort=${$('audit-sort').value}`);
        $('audit-prev').disabled=page.first;$('audit-next').disabled=page.last;$('audit-page').textContent=`${page.page+1} / ${Math.max(1,page.totalPages)}`;
        $('audit-body').innerHTML=page.content.map(item=>`<tr><td>${formatDate(item.occurredAt)}</td><td>${escapeHtml(item.actorEmail||'system')}</td><td>${escapeHtml(item.eventType)}</td><td>${escapeHtml(item.entityType||'—')}</td><td>${escapeHtml(item.summary)}</td><td><code>${escapeHtml(item.correlationId||'—')}</code></td></tr>`).join('');
    } catch(error){toast(error.message,true);}
}

function openPasswordDialog(required=false) {
    return new Promise(resolve => {
        const dialog = $('entity-dialog');
        const form = $('entity-form');

        const currentPasswordField = required
            ? ''
            : '<label>Текущий пароль<input name="current" type="password" required maxlength="128" autocomplete="current-password"></label>';

        form.innerHTML = `<div class="dialog-head"><h3>${required ? 'Установка постоянного пароля' : 'Смена пароля'}</h3>${required ? '' : '<button type="button" data-close-dialog="entity-dialog" class="icon-button">×</button>'}</div>
            ${currentPasswordField}
            <label>Новый пароль<input name="next" type="password" required minlength="12" maxlength="128" autocomplete="new-password"></label>
            <label>Повторите новый пароль<input name="confirm" type="password" required minlength="12" maxlength="128" autocomplete="new-password"></label>
            <p class="muted">Минимум 12 символов: верхний/нижний регистр, цифра и специальный символ.</p>
            <button class="primary">${required ? 'Установить пароль' : 'Изменить'}</button>`;

        let completed = false;

        const onClose = () => {
            dialog.removeEventListener('close', onClose);
            if (!completed) resolve(false);
        };
        dialog.addEventListener('close', onClose);

        form.onsubmit = async event => {
            event.preventDefault();

            const data = new FormData(form);
            const next = String(data.get('next') || '');
            const confirm = String(data.get('confirm') || '');

            if (next !== confirm) {
                toast('Новые пароли не совпадают', true);
                return;
            }

            try {
                if (required) {
                    await api('/auth/change-initial-password', {
                        method: 'POST',
                        body: JSON.stringify({newPassword: next})
                    });

                    /*
                     * Access JWT был выпущен до смены пароля и ещё содержит
                     * passwordChangeRequired=true. Refresh-cookie ротируется, после чего
                     * клиент получает новый access JWT с актуальным значением из БД.
                     */
                    const refreshed = await refreshAccessToken();
                    if (!refreshed) {
                        completed = true;
                        dialog.close();
                        showLogin();
                        toast('Пароль изменён, но сессию не удалось обновить. Войдите новым паролем.', true);
                        resolve(false);
                        return;
                    }

                    state.user.passwordChangeRequired = false;
                    completed = true;
                    dialog.close();
                    toast('Постоянный пароль установлен');
                    resolve(true);
                    return;
                }

                await api('/auth/change-password', {
                    method: 'POST',
                    body: JSON.stringify({
                        currentPassword: data.get('current'),
                        newPassword: next
                    })
                });

                completed = true;
                dialog.close();
                toast('Пароль изменён. Выполните вход заново.');
                resolve(true);
                await logout();
            } catch (error) {
                toast(error.message, true);
            }
        };

        dialog.showModal();
    });
}

async function logout() {
    try { await fetch('/api/auth/logout',{method:'POST',credentials:'include',headers:{'Content-Type':'application/json'},body:'{}'}); } catch (_) {}
    showLogin();
}

function bindEvents() {
    $('login-form').addEventListener('submit', async event => {
        event.preventDefault();

        const form = $('login-form');
        const button = form.querySelector('button[type="submit"]');
        const errorNode = $('login-error');

        errorNode.hidden = true;
        button.disabled = true;
        const originalCaption = button.textContent;
        button.textContent = 'Входим…';

        try {
            const response = await fetch('/api/auth/login', {
                method: 'POST',
                credentials: 'include',
                headers: {'Content-Type':'application/json'},
                body: JSON.stringify({
                    email: $('login-email').value,
                    password: $('login-password').value,
                    clientType: 'WEB'
                })
            });

            if (!response.ok) {
                throw new Error(await parseError(response));
            }

            const result = await response.json();
            state.accessToken = result.accessToken;
            state.user = result.user;
            $('login-password').value = '';

            await enterApplication();
        } catch (error) {
            /*
             * Если ошибка возникает после успешного HTTP login, экран входа снова
             * открывается и сообщение выводится в видимой форме.
             */
            console.error('[SPIN · Управление] Login flow failed', error);
            showLogin();
            errorNode.textContent = error.message || 'Не удалось выполнить вход';
            errorNode.hidden = false;
        } finally {
            button.disabled = false;
            button.textContent = originalCaption;
        }
    });
    $('navigation').addEventListener('click',event=>{const button=event.target.closest('[data-tab]');if(button&&!button.hidden)switchTab(button.dataset.tab);});
    $('logout').addEventListener('click',logout); $('change-password').addEventListener('click',()=>openPasswordDialog(false));
    $('search-orders').addEventListener('click',()=>{state.orders.query=$('order-search').value.trim();state.orders.page=0;loadStoreOrders(0);});
    $('order-search').addEventListener('keydown',event=>{if(event.key==='Enter')$('search-orders').click();});
    $('orders-sort').addEventListener('change',()=>loadStoreOrders(0));
    $('users-prev').addEventListener('click',()=>loadUsers(state.usersPage-1));$('users-next').addEventListener('click',()=>loadUsers(state.usersPage+1));
    $('audit-prev').addEventListener('click',()=>loadAudit(state.auditPage-1));$('audit-next').addEventListener('click',()=>loadAudit(state.auditPage+1));$('audit-sort').addEventListener('change',()=>loadAudit(0));
    $('orders-prev').addEventListener('click',()=>loadStoreOrders(state.orders.page-1)); $('orders-next').addEventListener('click',()=>loadStoreOrders(state.orders.page+1));
    $('add-order').addEventListener('click',()=>openStoreOrder()); $('bulk-action').addEventListener('click',bulkAction); $('export-csv').addEventListener('click',exportCsv);
    $('import-csv').addEventListener('click',()=>{$('csv-result').textContent='';$('csv-apply').disabled=true;$('csv-dialog').showModal();});
    $('add-record').addEventListener('click',()=>openRecord()); $('add-user').addEventListener('click',()=>openUser()); $('search-audit').addEventListener('click',loadAudit);
    $('orders-body').addEventListener('click',event=>handleStoreOrderTableClick(event));
    $('orders-body').addEventListener('click',async event=>{const b=event.target.closest('[data-permanent]');if(!b||!confirm('Окончательно удалить заказ? Восстановление будет невозможно.'))return;try{await api(`/orders/${b.dataset.permanent}/permanent`,{method:'DELETE',headers:{'X-Confirm-Delete':'PERMANENTLY_DELETE'}});await loadStoreOrders();await loadDashboard();}catch(e){toast(e.message,true);}});
    $('orders-body').addEventListener('change',event=>{const checkbox=event.target.closest('[data-order-select]');if(checkbox){checkbox.checked?state.selectedStoreOrderIds.add(checkbox.dataset.orderSelect):state.selectedStoreOrderIds.delete(checkbox.dataset.orderSelect);}});
    $('select-all-orders').addEventListener('change',event=>{document.querySelectorAll('[data-order-select]').forEach(cb=>{cb.checked=event.target.checked;event.target.checked?state.selectedStoreOrderIds.add(cb.dataset.orderSelect):state.selectedStoreOrderIds.delete(cb.dataset.orderSelect);});});
    $('records-body').addEventListener('click',event=>{const edit=event.target.closest('[data-record-edit]');const toggle=event.target.closest('[data-record-toggle]');if(edit)openRecord(edit.dataset.recordEdit);if(toggle)toggleRecord(toggle.dataset.recordToggle);});
    $('users-body').addEventListener('click',event=>{const edit=event.target.closest('[data-user-edit]');const reset=event.target.closest('[data-user-reset]');if(edit)openUser(edit.dataset.userEdit);if(reset)resetUserPassword(reset.dataset.userReset);});
    $('note-form').addEventListener('submit',async event=>{event.preventDefault();try{await api(`/orders/${state.activeStoreOrderForNotes}/notes`,{method:'POST',body:JSON.stringify({body:$('note-body').value,pinned:$('note-pinned').checked,version:null})});$('note-body').value='';$('note-pinned').checked=false;await renderNotes();}catch(error){toast(error.message,true);}});
    $('notes-list').addEventListener('click',async event=>{const button=event.target.closest('[data-note-delete]');if(button&&confirm('Удалить заметку?')){try{await api(`/orders/${state.activeStoreOrderForNotes}/notes/${button.dataset.noteDelete}`,{method:'DELETE'});await renderNotes();}catch(error){toast(error.message,true);}}});
    $('notes-list').addEventListener('click',async event=>{const button=event.target.closest('[data-note-edit]');if(!button)return;try{const list=await api(`/orders/${state.activeStoreOrderForNotes}/notes`);const note=list.find(n=>n.id===button.dataset.noteEdit);const body=prompt('Текст заметки',note.body);if(body===null)return;const pinned=confirm('Закрепить заметку?');await api(`/orders/${state.activeStoreOrderForNotes}/notes/${note.id}`,{method:'PUT',body:JSON.stringify({body,pinned,version:note.version})});await renderNotes();}catch(error){toast(error.message,true);}});
    $('csv-file').addEventListener('change',()=>{$('csv-apply').disabled=true;});
    $('csv-mode').addEventListener('change',()=>{$('csv-apply').disabled=true;});
    $('csv-preview').addEventListener('click',()=>runCsv(false)); $('csv-apply').addEventListener('click',()=>runCsv(true));
    document.body.addEventListener('click',event=>{const close=event.target.closest('[data-close-dialog]');if(close)$(close.dataset.closeDialog).close();});
}

function handleStoreOrderTableClick(event) {
    const view=event.target.closest('[data-order-view]'), edit=event.target.closest('[data-order-edit]'), notes=event.target.closest('[data-order-notes]'), archive=event.target.closest('[data-order-archive]');
    if(view)openStoreOrder(view.dataset.orderView,true); if(edit)openStoreOrder(edit.dataset.orderEdit,false); if(notes)openNotes(notes.dataset.orderNotes); if(archive)archiveStoreOrder(archive.dataset.orderArchive);
}

async function runCsv(apply) {
    const file=$('csv-file').files[0]; if(!file)return toast('Выберите CSV-файл',true);
    const form=new FormData();form.append('file',file);
    try{const result=await api(`/orders/import/${apply?'apply':'preview'}?mode=${$('csv-mode').value}`,{method:'POST',body:form});$('csv-result').textContent=JSON.stringify(result,null,2);$('csv-apply').disabled=apply?true:result.invalidRows>0;if(apply){toast('Импорт завершён');await Promise.all([loadStoreOrders(),loadDashboard()]);}}catch(error){toast(error.message,true);$('csv-result').textContent=error.message;}
}

function statusOptions(selected='NEW'){return ['NEW','PAID','CANCELED','COMPLETED','SHIPPED'].map(v=>`<option value="${v}" ${v===selected?'selected':''}>${statusLabel(v)}</option>`).join('');}
function statusLabel(value){return ({NEW:'Новый',PAID:'Оплачен',CANCELED:'Отменён',COMPLETED:'Завершён',SHIPPED:'Отправлен'})[value]||value;}
function priorityLabel(value){return ({NOT_APPLICABLE:'Не применяется',LOW:'Низкий',MEDIUM:'Средний',HIGH:'Высокий',CRITICAL:'Критический'})[value]||value;}
function formatDate(value){return value?new Intl.DateTimeFormat('ru-RU',{dateStyle:'short',timeStyle:'short'}).format(new Date(value)):'—';}
function empty(value){const normalized=String(value??'').trim();return normalized||null;}

boot().catch(error => {
    console.error('[SPIN · Управление] Boot failed', error);
    showLogin();
    toast(error.message || 'Не удалось инициализировать приложение', true);
});
