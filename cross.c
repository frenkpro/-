#include <GL/glut.h>
#include <GL/glu.h>
#include <GL/gl.h>
#include <GL/freeglut.h>
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <time.h>
#include <string.h>
#include "stb_easy_font.h"

clock_t start_time, end_time;
int number_slov = 0;
int field_size = 15, word_number = 2, wheel = 0;
char squares[50000] = { 0 };
char final_crossw[50][50] = { 0 };

void MouseClickedMenu(int button, int state, int x, int y);
void MouseClickedStart(int button, int state, int x, int y);

typedef struct {
    char* words;
    int used;
} Base;

typedef struct AVLNode {
    Base* data;
    struct AVLNode* left;
    struct AVLNode* right;
    int height;
} AVLNode;

int getHeight(AVLNode* node) {
    if (node == NULL)
        return 0;
    return node->height;
}

int getBalance(AVLNode* node) {
    if (node == NULL)
        return 0;
    return getHeight(node->left) - getHeight(node->right);
}

AVLNode* newNode(Base* data) {
    AVLNode* node = (AVLNode*)malloc(sizeof(AVLNode));
    node->data = data;
    node->left = NULL;
    node->right = NULL;
    node->height = 1; // новый узел имеет высоту 1
    return node;
}

AVLNode* rightRotate(AVLNode* y) {
    AVLNode* x = y->left;
    AVLNode* T2 = x->right;

    x->right = y;
    y->left = T2;

    if (getHeight(y->left) > getHeight(y->right))
        y->height = getHeight(y->left) + 1;
    else
        y->height = getHeight(y->right) + 1;

    if (getHeight(x->left) > getHeight(x->right))
        x->height = getHeight(x->left) + 1;
    else
        x->height = getHeight(x->right) + 1;

    return x;
}

AVLNode* leftRotate(AVLNode* x) {
    AVLNode* y = x->right;
    AVLNode* T2 = y->left;

    y->left = x;
    x->right = T2;

    
    if (getHeight(x->left) > getHeight(x->right))
        x->height = getHeight(x->left) + 1;
    else
        x->height = getHeight(x->right) + 1;

    if (getHeight(y->left) > getHeight(y->right))
        y->height = getHeight(y->left) + 1;
    else
        y->height = getHeight(y->right) + 1;

    return y;
}

AVLNode* insert(AVLNode* node, Base* data) {
    if (node == NULL)
        return newNode(data);

    if (strcmp(data->words, node->data->words) < 0)
        node->left = insert(node->left, data);
    else if (strcmp(data->words, node->data->words) > 0)
        node->right = insert(node->right, data);
    else
        return node; // дублирующиеся слова не допускаются


    if (getHeight(node->left) > getHeight(node->right))
        node->height = getHeight(node->left) + 1;
    else
        node->height = getHeight(node->right) + 1;

    int balance = getBalance(node);

    // Левый случай
    if (balance > 1 && strcmp(data->words, node->left->data->words) < 0)
        return rightRotate(node);

    // Правый случай
    if (balance < -1 && strcmp(data->words, node->right->data->words) > 0)
        return leftRotate(node);

    // Лево-правый случай
    if (balance > 1 && strcmp(data->words, node->left->data->words) > 0) {
        node->left = leftRotate(node->left);
        return rightRotate(node);
    }

    // Право-левый случай
    if (balance < -1 && strcmp(data->words, node->right->data->words) < 0) {
        node->right = rightRotate(node->right);
        return leftRotate(node);
    }

    return node;
}

void free_tree(AVLNode* root) {
    if (root != NULL) {
        free_tree(root->left);
        free_tree(root->right);
        free(root->data->words);
        free(root->data);
        free(root);
    }
}

Base* find_unused_word_with_symbol(AVLNode* root, char symbol, int promt) {
    if (root == NULL)
        return NULL;

    Base* result_left = find_unused_word_with_symbol(root->left, symbol,promt);

    if (result_left != NULL) {
        return result_left;  // Возвращаем результат из левого поддерева, если слово найдено там
    }

    if (root->data->used != -1 && root->data->used != promt && strchr(root->data->words, symbol) )
        return root->data;

    return find_unused_word_with_symbol(root->right, symbol,promt);
}

int attempt_to_place_vert(int i, int j, int n, char symbol, char* word, char** crossword) {
    int k = 0;

    for (int m = 0; m < strlen(word); m++) {
        if (word[m] == symbol) break;
        k++;
    }

    int start = i - k;
    int end = start + strlen(word);

    if ((start < 0) || (end - 1 >= n))
        return 0;

    for (i = start; i < end; i++) {
        if (crossword[i][j] != '0' && crossword[i][j] != word[k])
            return 0;

        if (j - 1 > -1 && (crossword[i][j - 1] != '0') && (crossword[i][j] != word[k]))
            return 0;

        if (j + 1 < n && (crossword[i][j + 1] != '0') && (crossword[i][j] != word[k]))
            return 0;
    }

    if (end < n && crossword[end][j] != '0')
        return 0;

    if (start - 1 > -1 && crossword[start - 1][j] != '0')
        return 0;

    return 1;
}

int attempt_to_place_horiz(int i, int j, int m, char symbol, char* word, char** crossword) {
    int k = 0;

    for (int m = 0; m < strlen(word); m++) {
        if (word[m] == symbol)
            break;

        k++;
    }

    int start = j - k;
    int end = start + strlen(word);

    if (start < 0 || end - 1 >= m)
        return 0;

    for (j = start; j < end; j++) {
        if (crossword[i][j] != '0' && crossword[i][j] != word[k])
            return 0;

        if (i - 1 > -1 && crossword[i - 1][j] != '0' && crossword[i][j] != word[k])
            return 0;

        if (i + 1 < m && crossword[i + 1][j] != '0' && crossword[i][j] != word[k])
            return 0;
    }

    if (end < m && crossword[i][end] != '0')
        return 0;

    if (start - 1 > -1 && crossword[i][start - 1] != '0')
        return 0;

    return 1;
}

void add_word_horizontal(char** crossword, char* word, int i, int start, int end) {
    for (int j = start, k = 0; j < end; j++, k++)
        crossword[i][j] = word[k];
}

void add_word_vertical(char** crossword, char* word, int j, int start, int end) {
    for (int i = start, k = 0; i < end; i++, k++)
        crossword[i][j] = word[k];
}

void place_first_word(char** crossword, int N, Base* tmp) {
    int i = rand() % (N / 2) + (N / 4);
    int len = strlen(tmp->words);

    int start = (rand() % (N - len));   // рандомно назначаем клетку старта
    int end = start + len;              // высчитываем клетку окончания 
    add_word_horizontal(crossword, tmp->words, i, start, end);
    tmp->used = -1;
}


// Графика
void print_string(float x, float y, char* text, float r, float g, float b)
{
    static char buffer[99999]; // ~500 chars
    int num_quads;

    num_quads = stb_easy_font_print(x, y, text, NULL, buffer, sizeof(buffer));

    glColor3f(r, g, b);
    glEnableClientState(GL_VERTEX_ARRAY);
    glVertexPointer(2, GL_FLOAT, 16, buffer);
    glDrawArrays(GL_QUADS, 0, num_quads * 4);
    glDisableClientState(GL_VERTEX_ARRAY);
}

void reshapeMenu(int w, int h)
{
    glViewport(0, 0, w, h);

    glMatrixMode(GL_PROJECTION);
    glLoadIdentity();
    gluOrtho2D(0, w, 0, h);

    glMatrixMode(GL_MODELVIEW);
    glLoadIdentity();
}

void DisplayMenu()
{
    glClearColor(0.85f, 0.85f, 0.95f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glBegin(GL_QUADS);
    glColor3f(0.0, 0.75, 1.0);
    // Start
    glVertex2i(100, 500);
    glVertex2i(100, 400);
    glVertex2i(500, 400);
    glVertex2i(500, 500);
    // About
    glVertex2i(100, 650);
    glVertex2i(100, 550);
    glVertex2i(500, 550);
    glVertex2i(500, 650);

    glColor3f(0.9, 0.35, 0.35);
    //Exit
    glVertex2i(100, 200);
    glVertex2i(100, 100);
    glVertex2i(500, 100);
    glVertex2i(500, 200);
    glEnd();

    // Logo
    glLineWidth(5);
    glBegin(GL_LINES);
    glColor3f(0.0, 0.75, 1.0);
    for (int i = 0; i < 9; i++)
    {
        glVertex2i(900 + 100 * i, 500);
        glVertex2i(900 + 100 * i, 600);

        glVertex2i(900 + 100 * i, 600);
        glVertex2i(1000 + 100 * i, 600);

        glVertex2i(1000 + 100 * i, 600);
        glVertex2i(1000 + 100 * i, 500);

        glVertex2i(1000 + 100 * i, 500);
        glVertex2i(900 + 100 * i, 500);
    }
    for (int i = 0; i < 9; i++)
    {
        glVertex2i(1600, 1000 - 100 * i);
        glVertex2i(1600, 900 - 100 * i);

        glVertex2i(1600, 900 - 100 * i);
        glVertex2i(1700, 900 - 100 * i);

        glVertex2i(1700, 900 - 100 * i);
        glVertex2i(1700, 1000 - 100 * i);

        glVertex2i(1700, 1000 - 100 * i);
        glVertex2i(1600, 1000 - 100 * i);
    }
    glEnd();


    glPushMatrix();
    glScalef(8, -8, 1);
    print_string(25, -79, "Start", 0, 0, 0.6);
    print_string(25, -60, "About", 0, 0, 0.6);
    print_string(28, -22, "Exit", 1, 1, 1);

    char logoword1[10][10] = { "C ", "R ", "O ", "S ", "S ", "W ", "O ", "R ", "D " };
    char logoword2[10][10] = { "G ", "E ", "N ", "E ", "R ", "A ", "T ", "O ", "R " };
    for (int i = 0; i < 9; i++) print_string(203.8, -121.7 + i * 12.4, logoword2[i], 0.0, 0.0, 0.6);
    for (int i = 0; i < 9; i++) print_string(117 + i * 12.4, -72, logoword1[i], 0.0, 0.0, 0.6);

    glPopMatrix();

    glutSwapBuffers();
}

void DisplayStart()
{
    glClearColor(0.85f, 0.85f, 0.95f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glBegin(GL_QUADS);

    glColor3f(0.0, 1.0, 0.0);
    // Generate
    glVertex2i(100, 800);
    glVertex2i(100, 700);
    glVertex2i(500, 700);
    glVertex2i(500, 800);

    glColor3f(0.0, 0.75, 1.0);
    // Words
    glVertex2i(100, 650);
    glVertex2i(100, 550);
    glVertex2i(500, 550);
    glVertex2i(500, 650);
    // Field Size
    glVertex2i(100, 500);
    glVertex2i(100, 400);
    glVertex2i(500, 400);
    glVertex2i(500, 500);

    glColor3f(0.9, 0.35, 0.35);
    // Backup
    glVertex2i(100, 200);
    glVertex2i(100, 100);
    glVertex2i(500, 100);
    glVertex2i(500, 200);

    glEnd();

    glBegin(GL_TRIANGLES);
    glColor3f(0.0, 0.75, 1.0);
    // Less Field
    glVertex2i(90, 500);
    glVertex2i(50, 450);
    glVertex2i(90, 400);
    // More Field
    glVertex2i(510, 500);
    glVertex2i(550, 450);
    glVertex2i(510, 400);

    glEnd();

    // Crossword Frame
    glLineWidth(3);
    glBegin(GL_LINES);
    float dif = 40;
    for (short int k1 = 0; k1 <= field_size; k1++)
    {
        glVertex2i(700 + dif * k1, 1050);
        glVertex2i(700 + dif * k1, 1050 - dif * (field_size));
        for (short int k2 = field_size; k2 > -1; k2--)
        {
            glVertex2i(700, 1050 - dif * k2);
            glVertex2i(700 + dif * (field_size), 1050 - dif * k2);
        }
    }
    glEnd();

    char field1[6] = { 0 };
    sprintf(field1, "%dx%d", field_size, field_size);
    glPushMatrix();
    glScalef(8, -8, 1);
    print_string(15, -98, "Generate", 0, 0, 0.6);
    print_string(24, -79, "Words", 0, 0, 0.6);
    if (field_size < 10) print_string(29, -60, field1, 0, 0, 0.6);
    else print_string(22, -60, field1, 0, 0, 0.6);
    print_string(26, -22, "Back", 1, 1, 1);
    glPopMatrix();

    glPushMatrix();
    glScalef(4, -4, 1);
    char temp[2] = { 0 };
    for (int i = 0; i < field_size; i++)
        for (int j = 0; j < field_size; j++)
        {
            if (final_crossw[i][j] != '0') temp[0] = final_crossw[i][j];
            else temp[0] = ' ';

            print_string(177.5 + 10 * j, -261 + 10 * i, temp, 0, 0, 0.6);
        }
    glPopMatrix();

    glPushMatrix();
    glScalef(4, -4, 1);
    char buff1[255] = { 0 };
    sprintf(buff1, "Execution time: %f", (double)(end_time - start_time) / CLOCKS_PER_SEC);
    print_string(25, -20, buff1, 0, 0, 0);
    glPopMatrix();

    glutSwapBuffers();
}

void DisplayAbout()
{
    glClearColor(0.85f, 0.85f, 0.95f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glBegin(GL_QUADS);

    glColor3f(0.9, 0.35, 0.35);
    // Backup
    glVertex2i(100, 200);
    glVertex2i(100, 100);
    glVertex2i(500, 100);
    glVertex2i(500, 200);

    glEnd();

    glPushMatrix();
    glScalef(8, -8, 1);
    print_string(26, -22, "Back", 1, 1, 1);
    glPopMatrix();

    glPushMatrix();
    glScalef(4, -4, 1);
    print_string(200, -200, "Created by:\nLeonov Fedor Akrisisovich\nAleksandrov Rodion Pavlovich", 0, 0, 0.6);
    print_string(200, -150, "From:\nSaint Petersburg Polytechnic University\nInstitute for Cyber Security and\nInformation Protection\nGroup 5151003/20002", 0, 0, 0.6);
    print_string(200, -75, "Year:\n2023-2024", 0, 0, 0.6);
    glPopMatrix();

    glutSwapBuffers();
}

void DisplayWords()
{
    glClearColor(0.85f, 0.85f, 0.95f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);


    glBegin(GL_QUADS);

    glColor3f(0.0, 0.75, 1.0);
    // Random words
    glVertex2i(1420, 980);
    glVertex2i(1420, 880);
    glVertex2i(1820, 880);
    glVertex2i(1820, 980);

    glColor3f(0, 1, 0);
    // All
    glVertex2i(970, 980);
    glVertex2i(970, 880);
    glVertex2i(1370, 880);
    glVertex2i(1370, 980);

    glColor3f(0.9, 0.35, 0.35);
    // Clear
    glVertex2i(520, 980);
    glVertex2i(520, 880);
    glVertex2i(920, 880);
    glVertex2i(920, 980);
    // Backup
    glVertex2i(100, 200);
    glVertex2i(100, 100);
    glVertex2i(500, 100);
    glVertex2i(500, 200);

    glEnd();

    glBegin(GL_TRIANGLES);
    glColor3f(0.0, 0.75, 1.0);
    // Key Down -17
    glVertex2i(1820, 150);
    glVertex2i(1720, 150);
    glVertex2i(1770, 100);
    glVertex2i(1820, 130);
    glVertex2i(1720, 130);
    glVertex2i(1770, 80);
    // Key Up +17
    glVertex2i(1770, 680);
    glVertex2i(1720, 630);
    glVertex2i(1820, 630);
    glVertex2i(1770, 660);
    glVertex2i(1720, 610);
    glVertex2i(1820, 610);
    // Key Down
    glVertex2i(1820, 230);
    glVertex2i(1720, 230);
    glVertex2i(1770, 180);
    // Key Up
    glVertex2i(1770, 580);
    glVertex2i(1720, 530);
    glVertex2i(1820, 530);

    glColor3f(0, 0, 0.6);
    // Key Down -100
    glVertex2i(1820, 60);
    glVertex2i(1720, 60);
    glVertex2i(1770, 10);
    // Key Up +100
    glVertex2i(1770, 750);
    glVertex2i(1720, 700);
    glVertex2i(1820, 700);

    glEnd();

    glColor3f(0.0, 0.75, 1.0);
    glLineWidth(5);
    glBegin(GL_LINES);
    // Borders between word list and the rest
    glVertex2i(700, 0);
    glVertex2i(700, 783);
    glVertex2i(700, 780);
    glVertex2i(1920, 780);

    glEnd();

    glPushMatrix();
    glScalef(4, -4, 1);
    FILE* dict = fopen("dict.txt", "r");
    
    char **word;
    word = (char**)calloc(number_slov, sizeof(*word));
    for (int i = 0; i < number_slov; i++) word[i] = (char*)calloc(20, sizeof(*word[i]));
    
    int counter1 = 0;
    for (int j = wheel; j > 0; j--) fscanf(dict, "%s", word[j]);
    for (int i = wheel; i < number_slov; i++)
    {
        glBegin(GL_QUADS);
        glColor3f(0, 0.75, 1);
        fscanf(dict, "%s", word[i]);
        if (squares[wheel + counter1])
        {
            glColor3f(0, 1, 0);
        }
        glVertex2i(180, -179 + 10 * counter1);
        glVertex2i(180, -174 + 10 * counter1);
        glVertex2i(185, -174 + 10 * counter1);
        glVertex2i(185, -179 + 10 * counter1);
        glEnd();
        print_string(190, -180 + 10 * counter1++, word[i], 0, 0, 0.6);
    }
    glPopMatrix();

    char amount[5] = { 0 };
    sprintf(amount, "%d", word_number);

    glPushMatrix();
    glScalef(8, -8, 1);
    print_string(26, -22, "Back", 1, 1, 1);
    print_string(185, -120, "Random", 0, 0, 0.6);
    print_string(141, -120, "All", 0, 0, 0.6);
    print_string(78, -120, "Clear", 1, 1, 1);
    glPopMatrix();

    glutSwapBuffers();

    FILE* need_dict = fopen("need_dict.txt", "w");
    for (int i = 0; i < number_slov; i++)
        if (squares[i]) fprintf(need_dict, "%s\n", word[i]);

    fclose(need_dict);
    fclose(dict);
}

void KeyboardWords(int key, int x, int y)
{
    if (key == GLUT_KEY_DOWN) if (wheel < 982) wheel += 1;
    if (key == GLUT_KEY_UP) if (wheel > 0) wheel -= 1;
    
    DisplayWords();
}

void MouseClickedWords(int button, int state, int x, int y)
{
    DisplayWords();
    // Cursors
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 1720 && x <= 1820 && y >= (1080 - 60) && y <= (1080 - 10)) if (wheel < number_slov - 101) wheel += 100;
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 1720 && x <= 1820 && y >= (1080 - 750) && y <= (1080 - 700)) if (wheel > 99) wheel -= 100;
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 1720 && x <= 1820 && y >= (1080 - 230) && y <= (1080 - 180)) if (wheel < number_slov - 18) wheel += 1;
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 1720 && x <= 1820 && y >= (1080 - 580) && y <= (1080 - 510)) if (wheel > 0) wheel -= 1;
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 1720 && x <= 1820 && y >= (1080 - 150) && y <= (1080 - 80)) if (wheel < number_slov - 28) wheel += 17;
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 1720 && x <= 1820 && y >= (1080 - 680) && y <= (1080 - 610)) if (wheel > 16) wheel -= 17;

    // Random words
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 1420 && x <= 1820 && y >= (1080 - 980) && y <= (1080 - 880))
    {
        srand(time(NULL));
        int j = 0;
        memset(squares, 0, sizeof(squares));
        int random_amount = rand() % number_slov + word_number;
        for (int i = rand() % number_slov; j < random_amount; i = rand() % number_slov)
        {
            if (squares[i] == 1) continue;
            else
            {
                j++;
                squares[i] = 1;
            }

        }
    }
    // Squares near words
    for (int i = 0; i < 18; i++)
        if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 720 && x <= 780 && y >= (1080 - 716 + 40 * i) && y <= (1080 - 696 + 40 * i))
            squares[wheel + i] = !squares[wheel + i];
    // Clear
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 520 && x <= 920 && y >= (1080 - 980) && y <= (1080 - 880)) memset(squares, 0, sizeof(squares));
    // All
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 970 && x <= 1370 && y >= (1080 - 980) && y <= (1080 - 880)) memset(squares, 1, sizeof(squares));
    // Backup
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 100 && x <= 500 && y >= (1080 - 200) && y <= (1080 - 100))
    {
        glutMouseFunc(MouseClickedStart);
        DisplayStart();
    }

}

void MouseClickedStart(int button, int state, int x, int y)
{
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 100 && x <= 500 && y >= (1080 - 800) && y <= (1080 - 700))
    {
        for (int i = 0; i < 25; i++)
            for (int j = 0; j < 25; j++)
                final_crossw[i][j] = '0';
                
        // Here crossword generator starts.
        AVLNode* root = NULL;
        char word[50];
        char** crossword;
        int size_dict = 0;

        int start = 0, end = 0, flag = 0;
        Base* tmp = NULL;
        srand((unsigned int)time(NULL));

        int max_len = 0, cur_len = 0;

        FILE* dictionary = fopen("need_dict.txt", "r");

        while (fscanf(dictionary, "%s", word) == 1) {
            Base* newBase = (Base*)malloc(sizeof(Base));
            if (strlen(word) > field_size - 1 && strlen(word) > field_size - 1)
                continue;

            newBase->words = strdup(word);
            newBase->used = 0;
            root = insert(root, newBase);
            cur_len = strlen(word);
            if (cur_len > max_len)
            {
                max_len = cur_len;
                tmp = newBase;
            }
            size_dict++;
        }

        crossword = (char**)malloc(field_size * sizeof(char*));
        for (int i = 0; i < field_size; i++) {
            crossword[i] = (char*)malloc(field_size * sizeof(char));
            memset(crossword[i], '0', field_size);
        }

        start_time = clock();

        place_first_word(crossword, field_size, tmp);
        // вставляем остальные слова
        for (int x = 0; x < 8; x++)
            for (int i = 0; i < field_size; i++) 
                for (int j = 0; j < field_size; j++) 
                    if (crossword[i][j] != '0')
                        for (int count = 0; count < size_dict; count++) {//count - количество просмотренных слов
                            tmp = find_unused_word_with_symbol(root, crossword[i][j], j);
                            int k = 0;
                            if (!tmp) break;
                            else if (tmp)
                            {
                                tmp->used = j;
                                if (attempt_to_place_vert(i, j, field_size, crossword[i][j], tmp->words, crossword) == 1) {
                                    for (int m = 0; m < strlen(tmp->words); m++) {
                                        if (tmp->words[m] == crossword[i][j]) break;
                                        k++;
                                    }
                                    start = i - k;
                                    end = start + strlen(tmp->words);
                                    add_word_vertical(crossword, tmp->words, j, start, end);
                                    tmp->used = -1;
                                }
                                else if (attempt_to_place_horiz(i, j, field_size, crossword[i][j], tmp->words, crossword) == 1) {
                                    for (int m = 0; m < strlen(tmp->words); m++) {
                                        if (tmp->words[m] == crossword[i][j]) break;
                                        k++;
                                    }
                                    start = j - k;
                                    end = start + strlen(tmp->words);
                                    add_word_horizontal(crossword, tmp->words, i, start, end);
                                    tmp->used = -1;
                                }
                            }
                        }
        
        end_time = clock();

        for (int i = 0; i < field_size; i++)
            for (int j = 0; j < field_size; j++)
                final_crossw[i][j] = crossword[i][j];

        for (int i = 0; i < field_size; i++)
            free(crossword[i]);
        free(crossword);
        free_tree(root);


        DisplayStart();
    }
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 100 && x <= 500 && y >= (1080 - 650) && y <= (1080 - 550))
    {
        glutMouseFunc(MouseClickedWords);
        glutSpecialFunc(KeyboardWords);
        DisplayWords();
    }
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 50 && x <= 90 && y >= (1080 - 500) && y <= (1080 - 400))
    {
        if (field_size > 5) field_size -= 1;
        DisplayStart();
    }
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 510 && x <= 550 && y >= (1080 - 500) && y <= (1080 - 400))
    {
        if (field_size < 25) field_size += 1;
        DisplayStart();
    }
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 100 && x <= 500 && y >= (1080 - 200) && y <= (1080 - 100))
    {
        DisplayMenu();
        glutMouseFunc(MouseClickedMenu);
    }
}

void MouseClickedAbout(int button, int state, int x, int y)
{
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 100 && x <= 500 && y >= (1080 - 200) && y <= (1080 - 100))
    {
        DisplayMenu();
        glutMouseFunc(MouseClickedMenu);
    }
}

void MouseClickedMenu(int button, int state, int x, int y)
{
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 100 && x <= 500 && y >= (1080 - 650) && y <= (1080 - 550))
    {
        DisplayStart();
        glutMouseFunc(MouseClickedStart);
    }
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 100 && x <= 500 && y >= (1080 - 500) && y <= (1080 - 400))
    {
        DisplayAbout();
        glutMouseFunc(MouseClickedAbout);
    }
    if (button == GLUT_LEFT_BUTTON && state == GLUT_DOWN && x >= 100 && x <= 500 && y >= (1080 - 200) && y <= (1080 - 100)) exit(0);
}

int main(int argc, char* argv[])
{
    FILE* crossw;
    crossw = fopen("dict.txt", "r");
    char dictionary_words[50] = {0};
    while (!feof(crossw) && fscanf(crossw, "%s", dictionary_words) == 1) number_slov++;
    fclose(crossw);

    srand(time(NULL));
    for (int i = 0; i < 1000; i++) squares[i] = 1;

    glutInit(&argc, argv);
    glutInitDisplayMode(GLUT_DOUBLE | GLUT_RGBA);
    glutCreateWindow("Crossword Generator");
    glutFullScreen();
    glutReshapeFunc(reshapeMenu);
    glutDisplayFunc(DisplayMenu);
    glutMouseFunc(MouseClickedMenu);

    glutMainLoop();

    return 0;
}
